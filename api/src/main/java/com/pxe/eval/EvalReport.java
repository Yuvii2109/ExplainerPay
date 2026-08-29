package com.pxe.eval;

import java.time.Instant;
import java.util.List;

/** What the harness produces, and what the /eval screen renders. */
public record EvalReport(
        Instant ranAt,
        int scenarios,
        int explained,
        List<Metric> metrics,
        List<Row> rows) {

    /**
     * One measured metric.
     *
     * <p>{@code numerator} and {@code denominator} are reported alongside {@code value} on purpose.
     * A zero with an empty denominator means nothing has been measured yet; a zero with a full one
     * means the system is failing. Those are different facts and a bare percentage hides the
     * difference.
     */
    public record Metric(
            String name,
            String definition,
            long numerator,
            long denominator,
            double value,
            String unit,
            String target,
            String enforcement,
            Status status) {

        public static Metric ratio(String name, String definition, long numerator, long denominator,
                                   String target, String enforcement, Status status) {
            double value = denominator == 0 ? 0.0 : (double) numerator / denominator;
            return new Metric(name, definition, numerator, denominator, value, "ratio", target,
                    enforcement, status);
        }
    }

    /** Whether a metric can be judged at all yet, and if so how it did. */
    public enum Status {
        /** Nothing to measure. The denominator is empty. */
        NOT_MEASURED,
        MET,
        NOT_MET
    }

    /**
     * One scenario, with the funnel test attached. Appendix A: if a scenario marked CODE ever
     * reaches the model, the funnel has regressed.
     */
    public record Row(
            String paymentId,
            String expectedPath,
            String actualPath,
            String injectedCause,
            String namedCause,
            boolean explained,
            int modelCalls,
            boolean pathAsExpected,
            boolean causeCorrect) {
    }
}
