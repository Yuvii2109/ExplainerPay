package com.pxe.deviation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 2 exit criterion: every scenario's computed deviation set equals its declared
 * {@code expected.deviations}. Requires the compose stack: {@code docker compose up -d}.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviationDetectionTest {

    @Autowired
    DeviationDetection detection;
    @Autowired
    ObjectMapper mapper;

    @Value("${pxe.scenarios-resource}")
    Resource scenarios;

    private JsonNode file() throws Exception {
        try (InputStream in = scenarios.getInputStream()) {
            return mapper.readTree(in);
        }
    }

    @Test
    void computedDeviationSetEqualsTheDeclaredOneForEveryScenario() throws Exception {
        JsonNode document = file();
        List<String> failures = new ArrayList<>();

        for (JsonNode scenario : document.get("scenarios")) {
            String id = scenario.get("id").asText();

            Set<String> declared = new TreeSet<>();
            scenario.get("expected").get("deviations").forEach(d -> declared.add(d.asText()));

            Set<String> computed = new TreeSet<>();
            detection.detect(detection.timelineOf(id)).forEach(d -> computed.add(d.name()));

            if (!computed.equals(declared)) {
                Set<String> extra = new TreeSet<>(computed);
                extra.removeAll(declared);
                Set<String> missing = new TreeSet<>(declared);
                missing.removeAll(computed);
                failures.add("%s extra=%s missing=%s".formatted(id, extra, missing));
            }
        }

        assertThat(failures).as("scenarios whose computed deviation set differs from the declared one")
                .isEmpty();
    }

    @Test
    void everyDeviationTypeInTheCatalogueIsExercisedByTheDataset() throws Exception {
        Set<DeviationType> seen = new java.util.HashSet<>();
        for (JsonNode scenario : file().get("scenarios")) {
            seen.addAll(detection.detect(detection.timelineOf(scenario.get("id").asText())));
        }
        assertThat(seen)
                .as("a detector with no scenario is a rule written before its failure mode, "
                        + "which non-negotiable rule 8 forbids")
                .containsExactlyInAnyOrder(DeviationType.values());
    }

    @Test
    void aDebitBeingReversedIsNotAnOrphanedDebit() {
        // PXE-010 and PXE-011 are both a debit without a credit. Only one is asymmetric.
        assertThat(detection.detect(detection.timelineOf("PXE-010")))
                .contains(DeviationType.AUTO_REVERSAL_PENDING)
                .doesNotContain(DeviationType.LEDGER_ASYMMETRY);
        assertThat(detection.detect(detection.timelineOf("PXE-011")))
                .contains(DeviationType.LEDGER_ASYMMETRY)
                .doesNotContain(DeviationType.AUTO_REVERSAL_PENDING);
    }

    @Test
    void anEventThatArrivedLateIsNotAnEventThatNeverArrived() {
        // PXE-015 exceeds the NETBANKING callback SLA by design and must not breach it.
        assertThat(detection.detect(detection.timelineOf("PXE-015")))
                .contains(DeviationType.LATE_CALLBACK)
                .doesNotContain(DeviationType.SLA_BREACHED, DeviationType.ABSENT_TERMINAL_EVENT);
        assertThat(detection.detect(detection.timelineOf("PXE-014")))
                .contains(DeviationType.SLA_BREACHED, DeviationType.ABSENT_TERMINAL_EVENT)
                .doesNotContain(DeviationType.LATE_CALLBACK);
    }

    @Test
    void aCleanSuccessDeviatesInNoWayAtAll() {
        // The two axes of section 10 agree here, and that is the whole of act 1.
        assertThat(detection.detect(detection.timelineOf("PXE-001"))).isEmpty();
        assertThat(detection.detect(detection.timelineOf("PXE-002"))).isEmpty();
        // A decline is a failure the rails fully explained. Failure is not deviation.
        assertThat(detection.detect(detection.timelineOf("PXE-003"))).isEmpty();
        assertThat(detection.detect(detection.timelineOf("PXE-004"))).isEmpty();
    }
}
