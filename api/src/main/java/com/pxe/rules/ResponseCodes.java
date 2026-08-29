package com.pxe.rules;

import java.util.Map;
import java.util.Optional;

/**
 * Section 11. The rail's own code is the cheapest explanation available.
 *
 * <p>The useful part of the table is the last column: codes sort into self-explaining and not, and
 * only the second group can justify a model call. At L2 the sort has one job, deciding whether a
 * terminal code leaves the outcome unexplained. Phase 4 uses the same sort to resolve L3a-i.
 *
 * <p>An unrecognised code is {@code NONE}. A code we cannot describe is by definition not one that
 * explains itself, and the conservative direction here is to admit a model call rather than to
 * assert an explanation nobody wrote.
 */
public final class ResponseCodes {

    public enum SelfExplaining {
        /** Says what happened and what it means. Nothing further is owed at L3. */
        FULL,
        /** Says what happened but not what follows. A rule usually completes it. */
        PARTIAL,
        /** Explains nothing on its own. This is the code that needs a model. */
        NONE
    }

    private static final Map<String, SelfExplaining> CATALOGUE = Map.of(
            "00", SelfExplaining.FULL,      // approved
            "Z9", SelfExplaining.FULL,      // insufficient funds
            "ZM", SelfExplaining.FULL,      // incorrect MPIN
            "ZA", SelfExplaining.FULL,      // declined by customer
            "U30", SelfExplaining.PARTIAL,  // debit timeout, deemed approved
            "U28", SelfExplaining.PARTIAL,  // beneficiary bank unavailable
            "BT", SelfExplaining.PARTIAL,   // beneficiary bank timed out
            "91", SelfExplaining.PARTIAL,   // issuer unavailable, card soft decline
            "96", SelfExplaining.NONE);     // system malfunction

    /**
     * L3a-i. The codes that are an explanation on their own, and the cause each one names.
     *
     * <p>{@code 00} is absent deliberately: approved is an outcome, not a cause, so a successful
     * payment that nonetheless owes an explanation falls through to the rule catalogue rather than
     * being told its cause was "approved". {@code ZA} is present because section 11 declares it
     * fully self-explaining; no scenario exercises it.
     */
    private static final Map<String, String> ROOT_CAUSE = Map.of(
            "Z9", "INSUFFICIENT_FUNDS",
            "ZM", "INCORRECT_MPIN",
            "ZA", "DECLINED_BY_CUSTOMER");

    private ResponseCodes() {
    }

    /** The cause this code names on its own, if it names one. */
    public static Optional<String> rootCause(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(ROOT_CAUSE.get(code));
    }

    public static SelfExplaining classify(String code) {
        return code == null ? SelfExplaining.NONE : CATALOGUE.getOrDefault(code, SelfExplaining.NONE);
    }

    public static boolean explainsNothing(String code) {
        return classify(code) == SelfExplaining.NONE;
    }
}
