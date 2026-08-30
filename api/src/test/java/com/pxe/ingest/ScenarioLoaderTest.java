package com.pxe.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.model.Payment;
import com.pxe.model.PaymentHop;
import com.pxe.model.PaymentHopRepository;
import com.pxe.model.PaymentReference;
import com.pxe.model.PaymentReferenceRepository;
import com.pxe.model.PaymentRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.pxe.support.Baseline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test. Requires the compose stack to be running: {@code docker compose up -d}.
 * It asserts against the same Postgres the demo uses, so a green run is a statement about the
 * real database rather than a disposable one.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScenarioLoaderTest {

    @Autowired
    ScenarioLoader loader;
    @Autowired
    PaymentRepository payments;
    @Autowired
    PaymentHopRepository hops;
    @Autowired
    PaymentReferenceRepository references;
    @Autowired
    ObjectMapper mapper;

    @Autowired
    Baseline baseline;
    @Autowired
    PaymentIntake intake;

    @Value("${pxe.scenarios-resource}")
    Resource scenarios;

    @BeforeEach
    void onlyTheGoldenSet() {
        baseline.deterministicOnly();
    }

    private JsonNode file() throws Exception {
        try (InputStream in = scenarios.getInputStream()) {
            return mapper.readTree(in);
        }
    }

    @Test
    void everyScenarioLoadsWithHopAndReferenceCountsMatchingTheFile() throws Exception {
        loader.load();
        JsonNode document = file();

        int expectedHops = 0;
        int expectedReferences = 0;

        for (JsonNode scenario : document.get("scenarios")) {
            String id = scenario.get("id").asText();

            Optional<Payment> loaded = payments.findById(id);
            assertThat(loaded).as("payment %s", id).isPresent();
            assertThat(loaded.get().getInstrument().name()).isEqualTo(scenario.get("instrument").asText());
            assertThat(loaded.get().getRail()).isEqualTo(scenario.get("rail").asText());
            assertThat(loaded.get().getAmountMinor()).isEqualTo(scenario.get("amountMinor").asLong());
            assertThat(loaded.get().getCurrency()).isEqualTo(scenario.get("currency").asText());

            JsonNode hopNodes = scenario.get("hops");
            long referencesInFile = 0;
            for (JsonNode hop : hopNodes) {
                if (hop.hasNonNull("reference")) {
                    referencesInFile++;
                }
            }

            assertThat(hops.countByPaymentId(id)).as("hop count for %s", id).isEqualTo(hopNodes.size());
            assertThat(references.countByPaymentId(id)).as("reference count for %s", id)
                    .isEqualTo(referencesInFile);

            List<PaymentHop> stored = hops.findByPaymentIdOrderBySeqAsc(id);
            for (int i = 0; i < hopNodes.size(); i++) {
                JsonNode inFile = hopNodes.get(i);
                PaymentHop inDb = stored.get(i);
                assertThat(inDb.getSeq()).isEqualTo(inFile.get("seq").asInt());
                assertThat(inDb.getStage()).isEqualTo(inFile.get("stage").asText());
                assertThat(inDb.getActor()).isEqualTo(inFile.get("actor").asText());
                assertThat(inDb.getStatus()).isEqualTo(inFile.get("status").asText());
                assertThat(inDb.getOccurredAt() == null).isEqualTo(!inFile.hasNonNull("at"));
            }

            expectedHops += hopNodes.size();
            expectedReferences += referencesInFile;
        }

        // Scoped to the golden set. The ambiguity cases live in the same tables and are counted
        // separately, because they answer a different question about the system.
        List<String> golden = new ArrayList<>();
        document.get("scenarios").forEach(s -> golden.add(s.get("id").asText()));

        assertThat(golden).hasSize(15);
        assertThat(golden.stream().mapToLong(hops::countByPaymentId).sum()).isEqualTo(expectedHops);
        assertThat(golden.stream().mapToLong(references::countByPaymentId).sum())
                .isEqualTo(expectedReferences);
    }

    @Test
    void referenceMutationAcrossARetryIsRepresentable() throws Exception {
        loader.load();

        List<PaymentReference> refs = references.findByPaymentIdOrderByHopSeqAsc("PXE-006");
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).getValue()).isEqualTo("445100000811");
        assertThat(refs.get(1).getValue()).isEqualTo("445100000812");
        assertThat(refs.get(0).getSupersededBy())
                .as("the pre-retry RRN is superseded by the post-retry one")
                .isEqualTo(refs.get(1).getId());
        assertThat(refs.get(1).getSupersededBy()).isNull();

        // A duplicate callback repeats one UTR. Repetition is not supersession.
        List<PaymentReference> duplicates = references.findByPaymentIdOrderByHopSeqAsc("PXE-009");
        assertThat(duplicates).hasSize(2);
        assertThat(duplicates.get(0).getValue()).isEqualTo(duplicates.get(1).getValue());
        assertThat(duplicates.get(0).getSupersededBy()).isNull();
    }

    @Test
    void noHopFieldInTheDatasetIsDropped() throws Exception {
        loader.load();
        JsonNode document = file();

        for (JsonNode scenario : document.get("scenarios")) {
            String id = scenario.get("id").asText();
            List<PaymentHop> stored = hops.findByPaymentIdOrderBySeqAsc(id);
            for (int i = 0; i < stored.size(); i++) {
                JsonNode inFile = scenario.get("hops").get(i);
                PaymentHop inDb = stored.get(i);
                inFile.fieldNames().forEachRemaining(field -> {
                    boolean isColumn = COLUMN_FIELDS.contains(field);
                    boolean isAttr = inDb.getAttrs().containsKey(field);
                    assertThat(isColumn || isAttr)
                            .as("hop field %s of %s seq %d is either a column or an attr",
                                    field, id, inDb.getSeq())
                            .isTrue();
                });
            }
        }
    }

    private static final List<String> COLUMN_FIELDS = List.of(
            "seq", "stage", "actor", "at", "status", "code", "latencyMs", "retryOf", "duplicateOf",
            "amountMinor", "batch", "cycle", "cutoffAt", "missedCutoff", "included",
            "boundReference", "note", "reference");

    @Test
    void rulePredicateFieldsAreTypedColumnsAndTheLongTailIsInAttrs() throws Exception {
        loader.load();

        // SETTLEMENT_CUTOFF_MISSED reads missedCutoff; RECON_RRN_MUTATION reads included
        // and boundReference. All three must be columns, not blob keys.
        PaymentHop scheduled = hops.findByPaymentIdOrderBySeqAsc("PXE-007").get(3);
        assertThat(scheduled.getMissedCutoff()).isTrue();
        assertThat(scheduled.getCutoffAt()).isNotNull();
        assertThat(scheduled.getCycle()).isEqualTo("T+1");

        PaymentHop batchClosed = hops.findByPaymentIdOrderBySeqAsc("PXE-006").get(8);
        assertThat(batchClosed.getIncluded()).isFalse();
        assertThat(hops.findByPaymentIdOrderBySeqAsc("PXE-006").get(7).getBoundReference())
                .isEqualTo("445100000811");

        // The absent node: no timestamp, and its evidence lives in attrs.
        PaymentHop absent = hops.findByPaymentIdOrderBySeqAsc("PXE-014").get(2);
        assertThat(absent.getOccurredAt()).isNull();
        assertThat(absent.getStatus()).isEqualTo("ABSENT");
        assertThat(absent.getAttrs()).containsKeys("elapsedHours", "slaHours");
    }

    @Test
    void takingAPaymentInCreatesANewOneRatherThanReopeningAnOld() throws Exception {
        long before = payments.count();

        PaymentIntake.Taken taken = intake.take("PXE-011", null, null, null);

        assertThat(taken.paymentId())
                .as("a scan is a payment going in, so it gets its own id")
                .startsWith("PAY-")
                .isNotEqualTo("PXE-011");
        assertThat(payments.count()).isEqualTo(before + 1);

        // It carries the same recorded events, and it went through the same funnel: its own hops,
        // its own deviations, its own debt.
        assertThat(hops.countByPaymentId(taken.paymentId()))
                .isEqualTo(hops.countByPaymentId("PXE-011"));
        assertThat(payments.findById(taken.paymentId()).orElseThrow().isDebtOpen())
                .as("no rule explains PXE-011, so the copy owes an explanation too")
                .isTrue();

        // And the golden record is untouched, which is what keeps the eval harness meaningful.
        assertThat(payments.findById("PXE-011")).isPresent();
    }

    @Test
    void theCustomerChoosesTheAmountAndEveryRecordedFigureMovesWithIt() throws Exception {
        // PXE-012 is settled short by a processing fee. Pay a different amount and the shortfall
        // has to move with it, or the timeline contradicts the total printed above it.
        PaymentIntake.Taken taken = intake.take("PXE-012", 50_000L, null, null);

        assertThat(taken.amountMinor()).isEqualTo(50_000L);
        assertThat(payments.findById(taken.paymentId()).orElseThrow().getAmountMinor())
                .isEqualTo(50_000L);

        long captured = hops.findByPaymentIdOrderBySeqAsc(taken.paymentId()).stream()
                .filter(h -> "CAPTURED".equals(h.getStage()))
                .findFirst().orElseThrow().getAmountMinor();
        long settled = hops.findByPaymentIdOrderBySeqAsc(taken.paymentId()).stream()
                .filter(h -> "PAYOUT_CREDITED".equals(h.getStage()))
                .findFirst().orElseThrow().getAmountMinor();

        assertThat(captured).isEqualTo(50_000L);
        assertThat(settled)
                .as("still short, in proportion, because a processing fee is a percentage")
                .isLessThan(captured)
                .isGreaterThan(0);
    }

    @Test
    void aShortfallTooSmallToPrintIsNotAShortfallOfZero() throws Exception {
        // Scaled far enough down, the fee would round away. Rounding a real difference to nothing
        // would turn a reconciliation break into a clean payment.
        PaymentIntake.Taken taken = intake.take("PXE-012", 10_000L, null, null);

        long captured = hops.findByPaymentIdOrderBySeqAsc(taken.paymentId()).stream()
                .filter(h -> "CAPTURED".equals(h.getStage()))
                .findFirst().orElseThrow().getAmountMinor();
        long settled = hops.findByPaymentIdOrderBySeqAsc(taken.paymentId()).stream()
                .filter(h -> "PAYOUT_CREDITED".equals(h.getStage()))
                .findFirst().orElseThrow().getAmountMinor();

        assertThat(settled).isNotEqualTo(captured);
    }

    @Test
    void groundTruthIsNotPersisted() throws Exception {
        loader.load();
        // The loader writes recorded fact only. `injectedCause`, `expected` and `explanation` have
        // nowhere to land: no column on any entity holds a declared cause, a declared path or a
        // declared deviation set. The eval harness reads them from the file instead.
        assertThat(payments.findById("PXE-006")).isPresent();
        assertThat(Payment.class.getDeclaredFields())
                .extracting("name")
                .doesNotContain("injectedCause", "rootCause", "path", "expectedPath",
                        "expectedDeviations", "declaredTag");
        // `tag` and `responseCode` are columns, but they are derived from the hops by the pipeline
        // and never read from the dataset. ExplanationPipelineTest is what proves they agree.
        assertThat(Payment.class.getDeclaredFields()).extracting("name").contains("tag");
    }
}
