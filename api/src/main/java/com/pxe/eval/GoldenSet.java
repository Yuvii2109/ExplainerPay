package com.pxe.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Ground truth, read from data/payment-scenarios.json and never from the database.
 *
 * <p>The simulator injects known failures, so the correct cause of every scenario is a fact rather
 * than an opinion. That is what converts explainability from a subjective quality into a measured
 * one. It is deliberately unreachable from the pipeline: the loader of phase 1 writes no part of it
 * to Postgres, so nothing that produces an explanation can consult the answer while producing it.
 */
@Component
public class GoldenSet {

    private static final Logger log = LoggerFactory.getLogger(GoldenSet.class);

    /** One scenario's ground truth. {@code injectedCause} is what a correct explanation must name. */
    public record Entry(
            String paymentId,
            String injectedCause,
            String expectedPath,
            String expectedRule,
            boolean explanationRequired,
            int expectedModelCalls) {

        /** PXE-014: the correct output is that the cause cannot be determined. */
        public boolean mustAbstain() {
            return "ABSTAIN".equals(expectedPath);
        }

        /** A scenario that owes an explanation naming a cause, so it can be scored for accuracy. */
        public boolean mustNameACause() {
            return explanationRequired && !mustAbstain() && injectedCause != null;
        }

        /** The funnel claim: NONE, CODE and RULE must resolve without spending a token. */
        public boolean mustCostNothing() {
            return expectedModelCalls == 0;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final double deterministicCoverageTarget;
    private final double causeAccuracyTarget;
    private final double falseAttributionTarget;
    private final double abstentionCorrectnessTarget;

    public GoldenSet(ObjectMapper mapper, ResourceLoader loader,
                     @Value("${pxe.scenarios-resource}") String location) throws IOException {
        Resource resource = loader.getResource(location);
        JsonNode document;
        try (InputStream in = resource.getInputStream()) {
            document = mapper.readTree(in);
        }
        for (JsonNode scenario : document.get("scenarios")) {
            JsonNode expected = scenario.get("expected");
            entries.add(new Entry(
                    scenario.get("id").asText(),
                    scenario.get("injectedCause").isNull() ? null : scenario.get("injectedCause").asText(),
                    expected.get("path").asText(),
                    expected.get("rule").isNull() ? null : expected.get("rule").asText(),
                    expected.get("explanationRequired").asBoolean(),
                    expected.get("modelCalls").asInt()));
        }
        JsonNode targets = document.get("coverageTargets");
        this.deterministicCoverageTarget = targets.get("deterministicCoverage").asDouble();
        this.causeAccuracyTarget = targets.get("causeAccuracy").asDouble();
        this.falseAttributionTarget = targets.get("falseAttribution").asDouble();
        this.abstentionCorrectnessTarget = targets.get("abstentionCorrectness").asDouble();

        log.info("golden set loaded from {}: {} scenarios, {} owing an explanation",
                resource.getDescription(), entries.size(),
                entries.stream().filter(Entry::explanationRequired).count());
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public double deterministicCoverageTarget() {
        return deterministicCoverageTarget;
    }

    public double causeAccuracyTarget() {
        return causeAccuracyTarget;
    }

    public double falseAttributionTarget() {
        return falseAttributionTarget;
    }

    public double abstentionCorrectnessTarget() {
        return abstentionCorrectnessTarget;
    }
}
