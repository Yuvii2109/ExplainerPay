/**
 * Internal symbols, said in English.
 *
 * MDR_FEE_APPLIED_TWICE_IN_BATCH is precise and it is the right thing to store, cite and test
 * against. It is the wrong thing to show a merchant. So the console leads with a human label and
 * keeps the symbol beside it in monospace: the person who needs to grep for it can still see it,
 * and the person who just wants their money explained does not have to decode it.
 *
 * `humanise` is the fallback and it is deliberately good, so a symbol nobody has written a label
 * for still reads as a sentence rather than as SHOUTING_SNAKE_CASE. Adding a cause to the taxonomy
 * never leaves a hole in the interface.
 */

/** Words that must not be sentence-cased. */
const ACRONYMS = new Set([
  "MDR",
  "RRN",
  "UTR",
  "ARN",
  "UPI",
  "QR",
  "SLA",
  "PSP",
  "NPCI",
  "MPIN",
  "FX",
  "ID",
  "L2",
  "L3",
  "L4",
]);

export function humanise(symbol: string | null | undefined): string {
  if (!symbol) return "";
  const words = symbol.split("_").filter(Boolean);
  return words
    .map((word, i) => {
      if (ACRONYMS.has(word.toUpperCase())) return word.toUpperCase();
      const lower = word.toLowerCase();
      return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
    })
    .join(" ");
}

/** Causes. Only the ones where the generic reading loses the mechanism. */
const CAUSES: Record<string, string> = {
  RRN_MUTATION_ON_RETRY: "Reference changed when the payment was retried",
  BANK_CUTOFF_MISSED_WEEKEND: "Missed the settlement cut-off before a non-working stretch",
  MDR_FEE_APPLIED_TWICE_IN_BATCH: "Processing fee charged twice in one batch",
  SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK: "Switch lost track of the payment after a partial acknowledgement",
  ISSUER_SOFT_DECLINE_RETRY_SUCCESS: "Issuer briefly unavailable, a later attempt succeeded",
  LATE_CALLBACK_AFTER_SESSION_CLOSE: "Bank confirmed after we had given up waiting",
  INTENT_EXPIRED_NO_ACTION: "The QR was never scanned",
  BENEFICIARY_BANK_UNAVAILABLE: "The receiving bank could not be reached",
  PARTIAL_CAPTURE_DRIFT: "Captured less than was authorized",
  DUPLICATE_CALLBACK: "The bank sent the same confirmation twice",
  INSUFFICIENT_FUNDS: "Not enough balance in the account",
  INCORRECT_MPIN: "Wrong UPI PIN",
  DECLINED_BY_CUSTOMER: "The customer cancelled it",
  INSTRUCTION_NEVER_TRANSMITTED: "The instruction never reached the bank",
  PAYOUT_INSTRUCTION_REJECTED_SILENTLY: "The bank dropped the payout without telling us",
  BENEFICIARY_ACCOUNT_FROZEN: "The receiving account is frozen",
  CLEARING_FILE_TRUNCATED_AT_SOURCE: "The clearing file was cut short at source",
  SWITCH_RESPONSE_LOST_IN_TRANSIT: "The switch reply never arrived",
  BATCH_CLOSED_BEFORE_INCLUSION: "The batch closed before the payment was added",
  CHARGEBACK_ADJUSTMENT_NETTED_IN_BATCH: "A chargeback was netted off in the same batch",
  UNDETERMINABLE_BY_DESIGN: "Undeterminable by design",
};

/** Deviations. What the expectation model noticed, in a phrase. */
const DEVIATIONS: Record<string, string> = {
  NO_CUSTOMER_ACTION: "Nobody scanned it",
  RRN_MUTATED: "Reference changed",
  UNSETTLED: "Never settled",
  BATCH_EXCLUSION: "Skipped by the batch",
  SETTLED_LATE: "Settled late",
  AMOUNT_MISMATCH: "Amounts disagree",
  DUPLICATE_SUPPRESSED: "Duplicate suppressed",
  AUTO_REVERSAL_PENDING: "Reversal in flight",
  UNEXPLAINED_TERMINAL: "Unexplained failure code",
  LEDGER_ASYMMETRY: "Debited but not credited",
  UNRECONCILED: "Ledger does not reconcile",
  RETRY_CASCADE: "Retried repeatedly",
  ABSENT_TERMINAL_EVENT: "Expected event never arrived",
  SLA_BREACHED: "Past its SLA",
  LATE_CALLBACK: "Callback arrived late",
  CUSTOMER_SAW_FAILURE: "Customer was shown a failure",
};

/** Outcome tags. What the rails said. */
const TAGS: Record<string, string> = {
  SUCCESS: "Succeeded",
  PENDING: "In flight",
  DEEMED_SUCCESS: "Deemed successful",
  DECLINED: "Declined",
  FAILED: "Failed",
  EXPIRED: "Expired",
  REVERSED: "Reversed",
};

const RAILS: Record<string, string> = {
  UPI_QR: "UPI QR",
  UPI_COLLECT: "UPI collect request",
  UPI_PAYOUT: "UPI payout",
  CARD_DOMESTIC: "Domestic card",
  NETBANKING: "Net banking",
};

const INSTRUMENTS: Record<string, string> = {
  UPI: "UPI",
  CARD: "Card",
  NETBANKING: "Net banking",
};

/** Hop stages. The timeline reads top to bottom, so these are noun phrases. */
const STAGES: Record<string, string> = {
  INTENT_CREATED: "Payment request created",
  QR_SCANNED: "QR scanned by customer",
  SWITCH_REQUEST: "Sent to the switch",
  SWITCH_RETRY: "Switch retried",
  SWITCH_RESPONSE: "Switch replied",
  PAYER_DEBIT: "Customer account debited",
  PAYEE_CREDIT: "Merchant account credited",
  SETTLEMENT_SCHEDULED: "Scheduled for settlement",
  BATCH_CLOSED: "Settlement batch closed",
  PAYOUT_CREDITED: "Paid out to merchant",
  PAYOUT_INSTRUCTED: "Payout instructed",
  PAYOUT_ACKNOWLEDGED: "Payout acknowledged",
  AUTH_REQUEST: "Authorization requested",
  RISK_EVALUATED: "Risk checked",
  NETWORK_AUTH: "Authorized by the network",
  CAPTURED: "Captured",
  AUTH_RESIDUAL_RELEASED: "Unused hold released",
  COLLECT_SENT: "Collect request sent",
  REDIRECT_ISSUED: "Customer sent to their bank",
  BANK_SESSION: "Customer at their bank",
  SESSION_ABANDONED: "We gave up waiting",
  CALLBACK_RECEIVED: "Bank called back",
  STATE_RECONCILED: "State reconciled",
  AUTO_REVERSAL_INITIATED: "Automatic reversal started",
  RECON_BREAK_DETECTED: "Reconciliation break found",
  INTENT_EXPIRED: "Request expired",
};

const ACTORS: Record<string, string> = {
  PXE: "Us",
  NPCI: "NPCI",
  CUSTOMER_PSP: "Customer app",
  PAYER_BANK: "Payer bank",
  PAYEE_BANK: "Payee bank",
  MERCHANT_BANK: "Merchant bank",
  NETWORK: "Card network",
  ISSUER: "Issuer",
};

/**
 * Response codes, as section 11 reads them.
 *
 * The code itself is what a support agent quotes to a bank, so it is never replaced, only
 * accompanied. A row that says only "96" tells a merchant nothing at all.
 */
const CODES: Record<string, string> = {
  "00": "Approved",
  Z9: "Not enough balance",
  ZM: "Wrong UPI PIN",
  ZA: "Cancelled by customer",
  U30: "Debit timed out",
  U28: "Beneficiary bank unreachable",
  BT: "Beneficiary bank timed out",
  "91": "Issuer unavailable",
  "96": "System malfunction",
};

const PATHS: Record<string, string> = {
  NONE: "Nothing owed",
  CODE: "Response code",
  RULE: "Rule",
  MODEL: "Model",
  ABSTAIN: "Abstained",
};

const RULES: Record<string, string> = {
  RECON_RRN_MUTATION: "Reference mutation at settlement",
  SETTLEMENT_CUTOFF_MISSED: "Settlement cut-off missed",
  AUTH_CAPTURE_DRIFT: "Partial capture",
  IDEMPOTENT_DUPLICATE: "Duplicate suppressed",
  BENEFICIARY_DOWN_AUTOREVERSE: "Beneficiary down, reversing",
  SOFT_DECLINE_RECOVERED: "Soft decline recovered",
  LATE_CALLBACK_RECONCILED: "Late callback reconciled",
  INTENT_EXPIRED_UNUSED: "Request expired unused",
};

const from = (map: Record<string, string>) => (symbol: string | null | undefined) =>
  !symbol ? "" : (map[symbol] ?? humanise(symbol));

export const cause = from(CAUSES);
export const deviation = from(DEVIATIONS);
export const tag = from(TAGS);
export const rail = from(RAILS);
export const instrument = from(INSTRUMENTS);
export const stage = from(STAGES);
export const actor = from(ACTORS);
export const path = from(PATHS);
export const rule = from(RULES);
export const code = from(CODES);

/** The empty cell. A word beats a dash, because a dash could mean anything. */
export const NONE = "none";

/** "hop:4" reads as "hop 4"; "rule:RECON_RRN_MUTATION" reads as the rule it names. */
export function citation(raw: string): string {
  const [kind, ...rest] = raw.split(":");
  const value = rest.join(":");
  if (kind === "hop") return `hop ${value}`;
  if (kind === "rule") return rule(value);
  if (kind === "code") return `code ${value}`;
  if (kind === "ref") return `reference ${value}`;
  return raw;
}

/** OK, TIMEOUT, DUPLICATE_SUPPRESSED, short enough to read as-is once de-shouted. */
export function status(raw: string | null | undefined): string {
  if (!raw) return "";
  if (raw === "OK") return "OK";
  return humanise(raw);
}
