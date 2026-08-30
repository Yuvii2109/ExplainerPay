package com.pxe.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pxe.deviation.DeviationDetection;
import com.pxe.eval.EvalHarness;
import com.pxe.eval.EvalReport;
import com.pxe.model.Payment;
import com.pxe.model.PaymentRepository;
import com.pxe.rules.RuleCatalogue;
import com.pxe.support.Baseline;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 4 exit criterion: deterministic coverage reaches 80%, and the twelve non-MODEL scenarios
 * resolve with zero model calls. Requires the compose stack: {@code docker compose up -d}.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExplanationPipelineTest {

    @Autowired
    ExplanationPipeline pipeline;
    @Autowired
    EvalHarness harness;
    @Autowired
    PaymentRepository payments;
    @Autowired
    ExplanationRepository explanations;
    @Autowired
    RuleHitRepository ruleHits;
    @Autowired
    RuleCatalogue rules;
    @Autowired
    DeviationDetection detection;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    Baseline baseline;

    @Value("${pxe.scenarios-resource}")
    Resource scenarios;

    @BeforeEach
    void resolve() {
        baseline.deterministicOnly();
    }

    private JsonNode file() throws Exception {
        try (InputStream in = scenarios.getInputStream()) {
            return mapper.readTree(in);
        }
    }

    private EvalReport.Metric metric(String name) {
        return harness.run().metrics().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void deterministicCoverageIsEightyPercent() {
        EvalReport.Metric coverage = metric("Deterministic coverage");
        assertThat(coverage.numerator()).isEqualTo(12);
        assertThat(coverage.denominator()).isEqualTo(15);
        assertThat(coverage.value()).isEqualTo(0.8);
        assertThat(coverage.status()).isEqualTo(EvalReport.Status.MET);
    }

    @Test
    void theTwelveNonModelScenariosResolveWithZeroModelCalls() {
        EvalReport report = harness.run();
        List<EvalReport.Row> free = report.rows().stream()
                .filter(r -> !"MODEL".equals(r.expectedPath()) && !"ABSTAIN".equals(r.expectedPath()))
                .toList();

        assertThat(free).hasSize(12);
        assertThat(free).allSatisfy(r -> assertThat(r.modelCalls()).isZero());
        assertThat(report.rows()).allSatisfy(r -> assertThat(r.modelCalls()).isZero());
    }

    @Test
    void theFunnelHasNotRegressed() throws Exception {
        EvalReport report = harness.run();
        assertThat(harness.funnelRegression(report)).isEmpty();

        // Every scenario that produced an explanation took the path the dataset declared.
        for (JsonNode scenario : file().get("scenarios")) {
            String id = scenario.get("id").asText();
            String declared = scenario.get("expected").get("path").asText();
            var produced = explanations.findByPaymentId(id);
            if (List.of("NONE", "MODEL", "ABSTAIN").contains(declared)) {
                assertThat(produced).as("%s must not be explained deterministically", id).isEmpty();
            } else {
                assertThat(produced).as("%s takes the %s path", id, declared).isPresent();
                assertThat(produced.get().getPath()).isEqualTo(declared);
            }
        }
    }

    @Test
    void everyTagAndResponseCodeMatchesTheDeclaredOutcome() throws Exception {
        List<String> failures = new ArrayList<>();
        for (JsonNode scenario : file().get("scenarios")) {
            String id = scenario.get("id").asText();
            JsonNode expected = scenario.get("expected");
            Payment payment = payments.findById(id).orElseThrow();

            String declaredTag = expected.get("tag").asText();
            String declaredCode = expected.get("responseCode").isNull()
                    ? null : expected.get("responseCode").asText();

            if (!declaredTag.equals(payment.getTag().name())) {
                failures.add("%s tag: declared %s, resolved %s"
                        .formatted(id, declaredTag, payment.getTag()));
            }
            if (!java.util.Objects.equals(declaredCode, payment.getResponseCode())) {
                failures.add("%s code: declared %s, resolved %s"
                        .formatted(id, declaredCode, payment.getResponseCode()));
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void debtIsOpenedByFailureAndClosedByExplanation() throws Exception {
        for (JsonNode scenario : file().get("scenarios")) {
            String id = scenario.get("id").asText();
            int declaredDebt = scenario.get("expected").get("debt").asInt();
            Payment payment = payments.findById(id).orElseThrow();

            if (declaredDebt == 0) {
                assertThat(payment.getDebtOpenedAt()).as("%s never owed anything", id).isNull();
                assertThat(payment.isDebtOpen()).isFalse();
            } else {
                assertThat(payment.getDebtOpenedAt()).as("%s incurred a debt", id).isNotNull();
            }
        }

        // The three golden payments still open are exactly the three no rule can explain. The
        // ambiguity cases are open too, by construction, and are asserted separately.
        assertThat(payments.findByDebtOpenTrueOrderByAmountMinorDesc())
                .extracting(Payment::getId)
                .filteredOn(id -> id.startsWith("PXE-"))
                .containsExactlyInAnyOrder("PXE-011", "PXE-012", "PXE-014");
    }

    @Test
    void theDebtQueueIsSortedByExposure() {
        List<String> queue = payments.findByDebtOpenTrueOrderByAmountMinorDesc().stream()
                .map(Payment::getId)
                .filter(id -> id.startsWith("PXE-"))
                .toList();
        assertThat(queue).containsExactly("PXE-012", "PXE-014", "PXE-011");
    }

    @Test
    void everyAmbiguityCaseReachesTheModelWithNoRuleExplainingIt() {
        // The point of the set: a plausible answer is available and the deterministic funnel
        // correctly refuses to supply one. If a rule ever fired here, the case would have stopped
        // being ambiguous and would need rewriting rather than the rule loosening.
        assertThat(payments.findByDebtOpenTrueOrderByAmountMinorDesc())
                .extracting(Payment::getId)
                .contains("AMB-001", "AMB-002", "AMB-003", "AMB-004");
    }

    @Test
    void everyRuleInTheCatalogueIsExercisedAndNoTwoRulesOverlap() throws Exception {
        List<String> fired = ruleHits.findAll().stream().map(RuleHit::getRuleId).sorted().toList();
        assertThat(fired).containsExactlyInAnyOrderElementsOf(rules.ruleIds());

        for (JsonNode scenario : file().get("scenarios")) {
            String id = scenario.get("id").asText();
            assertThat(rules.allMatches(detection.timelineOf(id)))
                    .as("%s must match at most one rule, or the order becomes a hidden priority", id)
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    void everyExplanationNamesTheRuleTheDatasetDeclares() throws Exception {
        for (JsonNode scenario : file().get("scenarios")) {
            JsonNode expected = scenario.get("expected");
            if (expected.get("rule").isNull()) {
                continue;
            }
            String id = scenario.get("id").asText();
            assertThat(ruleHits.findByPaymentId(id))
                    .as("%s is explained by %s", id, expected.get("rule").asText())
                    .extracting(RuleHit::getRuleId)
                    .containsExactly(expected.get("rule").asText());
        }
    }

    @Test
    void theSameFactSetProducesTheSameHash() {
        var before = explanations.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Explanation::getPaymentId, Explanation::getFactSetHash));
        pipeline.resolveAll();
        var after = explanations.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Explanation::getPaymentId, Explanation::getFactSetHash));

        assertThat(after).as("an explanation whose inputs have not changed is never regenerated")
                .isEqualTo(before);
        assertThat(metric("Determinism").status()).isEqualTo(EvalReport.Status.MET);
    }

    @Test
    void theDeterministicPathsNeverNameAWrongCause() {
        EvalReport.Metric falseAttribution = metric("False attribution");
        assertThat(falseAttribution.numerator())
                .as("a confident wrong cause is worse in operations than an abstention, and a "
                        + "response code or a rule can only ever name the cause it encodes")
                .isZero();
        assertThat(falseAttribution.denominator()).isEqualTo(10);
        assertThat(falseAttribution.status()).isEqualTo(EvalReport.Status.MET);
    }

    @Test
    void theDeterministicFunnelAloneReachesTenOfTwelveCauses() {
        EvalReport.Metric accuracy = metric("Cause accuracy");
        assertThat(accuracy.numerator()).isEqualTo(10);
        assertThat(accuracy.denominator()).isEqualTo(12);
        assertThat(accuracy.status())
                .as("PXE-011 and PXE-012 are the missing two, and they are a MODEL-path problem")
                .isEqualTo(EvalReport.Status.NOT_MET);
    }

    @Test
    void aCleanSuccessGetsNoExplanationRowAtAll() {
        assertThat(explanations.findByPaymentId("PXE-001")).isEmpty();
        assertThat(explanations.findByPaymentId("PXE-002")).isEmpty();
        assertThat(payments.findById("PXE-001").orElseThrow().getDebtOpenedAt()).isNull();
    }
}
