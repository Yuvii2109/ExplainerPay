package com.pxe.deviation;

/** The closed vocabulary of section 9.1. Sixteen types, each with one detector. */
public enum DeviationType {
    NO_CUSTOMER_ACTION,
    RRN_MUTATED,
    UNSETTLED,
    BATCH_EXCLUSION,
    SETTLED_LATE,
    AMOUNT_MISMATCH,
    DUPLICATE_SUPPRESSED,
    AUTO_REVERSAL_PENDING,
    UNEXPLAINED_TERMINAL,
    LEDGER_ASYMMETRY,
    UNRECONCILED,
    RETRY_CASCADE,
    ABSENT_TERMINAL_EVENT,
    SLA_BREACHED,
    LATE_CALLBACK,
    CUSTOMER_SAW_FAILURE
}
