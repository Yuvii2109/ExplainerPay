package com.pxe.grounding;

import java.util.List;

/**
 * The verdict of the grounding contract on one generated response.
 *
 * <p>Two outcomes that must never be confused: a response can be <em>rejected</em>, in which case
 * nothing it said survives, or it can be <em>reduced</em>, in which case the claims that failed
 * were dropped and the rest stand. Section 15 assigns each rule to one of those, and the difference
 * is the difference between a protocol violation and a content error.
 */
public record Grounding(
        boolean rejected,
        String rejectedBy,
        String detail,
        List<Claim> kept,
        List<Dropped> dropped,
        int numbersRendered,
        int numbersMatchingTheLedger) {

    /** A claim that survived, with its placeholders already resolved from typed fields. */
    public record Claim(String id, String kind, String text, List<String> citations) {
    }

    /** A claim that did not survive, and the rule that removed it. An uncited claim is removed. */
    public record Dropped(String id, String rule, String why) {
    }

    public static Grounding reject(String rule, String detail) {
        return new Grounding(true, rule, detail, List.of(), List.of(), 0, 0);
    }

    public boolean hasFact() {
        return kept.stream().anyMatch(c -> "FACT".equals(c.kind()));
    }

    /** G8: if every FACT claim is dropped, do not render L4. */
    public boolean rendersNarrative() {
        return !rejected && hasFact();
    }
}
