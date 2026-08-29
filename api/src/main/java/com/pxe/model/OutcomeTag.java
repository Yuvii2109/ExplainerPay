package com.pxe.model;

/**
 * Section 10. Every payment carries a terminal tag from a closed vocabulary. For a clean success it
 * is the only thing shown.
 *
 * <p>The tag is what the rails said. The deviation set is what the expectation model says. They are
 * independent axes and they disagree more often than is comfortable: PXE-007 is a SUCCESS that owes
 * an explanation.
 */
public enum OutcomeTag {
    /** Debited, credited, confirmed. The only tag that can owe nothing. */
    SUCCESS,
    /** In flight, inside SLA. */
    PENDING,
    /** Debit confirmed, switch confirmation timed out. */
    DEEMED_SUCCESS,
    /** Explicitly refused by customer or issuer. */
    DECLINED,
    /** Technical failure at some hop. */
    FAILED,
    /** Intent lapsed with no customer action. */
    EXPIRED,
    /**
     * Debited then auto-reversed. No scenario in the golden set produces it, so nothing resolves to
     * it: non-negotiable rule 8 says the failure mode enters the dataset before the code that names
     * it. It is here because section 10 closes the vocabulary, not because it is reachable.
     */
    REVERSED
}
