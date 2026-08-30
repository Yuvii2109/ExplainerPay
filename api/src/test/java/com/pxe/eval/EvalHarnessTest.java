package com.pxe.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.pxe.eval.EvalReport.Metric;
import com.pxe.eval.EvalReport.Status;
import com.pxe.support.Baseline;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The harness itself: its shape, its denominators and its refusal to conflate two kinds of zero.
 *
 * <p>Phase 3's exit criterion, every metric zero because nothing explains yet, was demonstrated
 * when nothing had run at all. It is not restorable now: once outcomes are resolved, the two clean
 * successes are deterministically covered whether or not any explanation exists, which is the
 * correct answer and not a regression. What remains testable is everything below.
 *
 * <p>Requires the compose stack: {@code docker compose up -d}.
 */
@SpringBootTest
@ActiveProfiles("test")
class EvalHarnessTest {

    @Autowired
    EvalHarness harness;
    @Autowired
    GoldenSet golden;
    @Autowired
    Baseline baseline;

    @org.junit.jupiter.api.BeforeEach
    void deterministicBaseline() {
        baseline.deterministicOnly();
    }

    private Metric metric(EvalReport report, String name) {
        return report.metrics().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metric named " + name));
    }

    @Test
    void theHarnessRunsAndReportsEveryMetricInSection22() {
        EvalReport report = harness.run();

        assertThat(report.metrics()).extracting(Metric::name).containsExactly(
                "Groundedness",
                "Numeric fidelity",
                "Cause accuracy",
                "False attribution",
                "Abstention correctness",
                "Determinism",
                "Deterministic coverage",
                "Cost per explained payment");
        assertThat(report.scenarios())
                .as("the golden set is what coverage and accuracy are measured over")
                .isEqualTo(15);
        assertThat(report.rows())
                .as("rows cover the golden set plus the ambiguity cases")
                .hasSize(19);
    }

    @Test
    void aZeroMeaningNothingYetIsDistinguishableFromAZeroMeaningFailing() {
        EvalReport report = harness.run();

        // Empty denominator: there is nothing to measure, and the harness says so rather than
        // reporting a 0% that reads as failure. Both stay this way until phase 7 supplies them.
        assertThat(List.of("Groundedness", "Numeric fidelity"))
                .allSatisfy(name -> {
                    assertThat(metric(report, name).denominator()).as("%s denominator", name).isZero();
                    assertThat(metric(report, name).value()).isZero();
                    assertThat(metric(report, name).status()).isEqualTo(Status.NOT_MEASURED);
                });

        // Real denominators, fixed by the golden set and independent of what has been produced.
        assertThat(metric(report, "Cause accuracy").denominator())
                .as("scenarios owing a named cause: all but the two clean successes and the abstention")
                .isEqualTo(12);
        assertThat(metric(report, "Abstention correctness").denominator())
                .as("PXE-014 plus the four ambiguity cases; a metric measured on one case is not "
                        + "measured")
                .isEqualTo(5);
        assertThat(metric(report, "Deterministic coverage").denominator()).isEqualTo(15);
    }

    @Test
    void theDeterministicFunnelSpendsNothingAndDoesNotRegress() {
        EvalReport report = harness.run();
        assertThat(harness.funnelRegression(report)).isEmpty();
        assertThat(report.rows())
                .as("if a scenario marked CODE ever reaches the model, the funnel has regressed")
                .allSatisfy(r -> assertThat(r.modelCalls()).isZero());
    }

    @Test
    void groundTruthComesFromTheFileAndTheTargetsComeWithIt() {
        assertThat(golden.entries()).hasSize(15);
        assertThat(golden.entries()).filteredOn(GoldenSet.Entry::mustAbstain)
                .extracting(GoldenSet.Entry::paymentId)
                .containsExactly("PXE-014");
        assertThat(golden.entries()).filteredOn(GoldenSet.Entry::mustCostNothing).hasSize(12);
        assertThat(golden.deterministicCoverageTarget()).isEqualTo(0.8);
        assertThat(golden.causeAccuracyTarget()).isEqualTo(0.85);
        assertThat(golden.falseAttributionTarget()).isEqualTo(0.02);
        assertThat(golden.abstentionCorrectnessTarget()).isEqualTo(0.9);
    }
}
