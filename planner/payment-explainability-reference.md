# Payment Explainability Engine

## A self-contained build document

**This file is portable on purpose.** It assumes no code, no repository and no prior work. Copy it
into an empty directory and it contains everything needed to build the project described in it:
the domain model, the schema, the rule catalogue, the AI contract, the dataset, the UI
specification and a phased build order with observable exit criteria.

Nothing in this document depends on the LaserPay repository it currently sits in. That project stays
as it is.

---

# Part I: What You Are Building

## 1. The product in one paragraph

A payment goes in. It traverses a real multi-stage backend: intent, switch, payer bank, payee bank,
settlement, payout, reconciliation. Every hop is recorded. When it succeeds cleanly, the system says
so and nothing more. When it fails, stalls, or succeeds in a way that does not reconcile, the system
**owes an explanation** and pays that debt: what happened, why, grounded in the actual events, in
language matched to whoever is asking.

## 2. Why this problem

*"Where is my settlement?"* and *"why did this payment fail when the customer says they were
debited?"* are the highest-volume support questions at every payment company on earth. They are
answered today by a human reading logs, and the answer is thrown away as soon as it is given.

Three properties make it an unusually good target for applied AI:

**Ground truth is knowable.** A payment's cause of failure is a fact, so the system can be tested
against it. That makes the AI component *evaluable*, which is the difference between an engineering
artifact and a demo.

**The volume forbids naive AI.** Sending millions of payments a day to a model is economically
absurd and operationally unauditable. The correct architecture sends almost none of them, and being
able to say precisely which and why is the interesting part.

**A wrong answer is asymmetrically expensive.** A confidently wrong explanation about where money
went gets repeated to a customer, entered into a ticket, and acted upon. That forces a design where
the model's output is constrained rather than trusted.

## 3. The two ideas the whole build rests on

### Idea one: explanation is a debt, and only failure incurs it

```
outcome is SUCCESS and no deviation   ->  debt = 0, no narrative, no model, no tokens
anything else                         ->  debt = 1, unpaid until explained
```

Explaining a success is the most common way an AI feature wastes money: the answer is always the
same and nobody asked. A clean payment gets a tag, a timeline and a settlement date. That is all.

"Anything else" deliberately includes a payment that succeeded **badly**: credited but eleven days
late, captured but unreconciled, settled for an amount the ledger disagrees with. The customer got
their money and something is still wrong. That is exactly the class ops teams miss.

Modelling it as a debt rather than a flag gives you three things: a **work queue** sorted by
exposure, a **service level** (time from failure to explanation, which predicts support load better
than success rate), and a number on the console that should trend to zero.

**An abstention does not pay the debt.** A response code pays it, a rule pays it, and a cited
hypothesis pays it. "Cannot be determined" is the correct output when the evidence does not reach a
cause, and it is still an admission that nobody has explained the payment: the money is missing, the
merchant is owed an answer, and a human now has to go and get one. Closing the debt there would let
the console trend to zero by giving up, which is the one way to make the number lie.

So an abstention produces an explanation record, renders all three audiences, and leaves
`debt_open = true`. PXE-014 stays on the queue until somebody resolves it. That is the intended
end state of the demo: the debt falls but does not reach zero, and the one payment still on the
queue is the one the system was honest about.

### Idea two: explainability is six rungs, climbed in order

| Rung | Name | Produced by | Can it be wrong? |
| --- | --- | --- | --- |
| **L0** | Raw events | Ingestion | No. It is the record. |
| **L1** | Reconstructed timeline | Deterministic | No, but it can be incomplete. |
| **L2** | Deviation from expectation | Deterministic | No. Expectation is policy. |
| **L3** | Causal attribution | Rules first, model only if no rule fires | Yes. All risk lives here. |
| **L4** | Narrative | Model | Yes, but only in wording. |
| **L5** | Recommended action | Policy | No. The model does not choose. |

Most systems claiming explainability jump L0 to L4 and let the model invent the middle in prose.
That is where hallucinated timelines and imagined amounts come from. **Everything below L3 is
computed. L4 is generation over a fact set that is already fixed.**

## 4. Worked example

A UPI payment of 18,400 has not settled. The merchant asks where it is.

```
L0  17 events between 2026-03-08T10:00:03Z and 2026-03-12T02:15:00Z

L1  authorized 10:00:03 -> risk PASS 10:00:04 -> switch 10:00:05
    -> payer debit TIMEOUT U30 10:00:14, RRN 445100000811
    -> retry 10:00:44 -> debit OK, RRN 445100000812
    -> payee credit 10:00:45 -> batch SB-9931 scheduled T+1
    -> batch SB-9931 CLOSED Mar 09, this payment NOT INCLUDED

L2  Expected credit by 2026-03-09T18:00. Actual: none.
    Deviations: RRN_MUTATED, UNSETTLED, BATCH_EXCLUSION.

L3  Rule RECON_RRN_MUTATION matched. The retry succeeded under a new
    reference; the settlement selector binds the reference captured at
    schedule time. The payment is captured, owed, and invisible to the batch.

L4  "Your money is not lost. The first attempt timed out and the automatic
     retry succeeded, but under a new reference number. The settlement run
     picks payments up by their original reference, so this one was skipped
     rather than rejected. It is captured and owed to you."

L5  REBIND_RRN_AND_REQUEUE, requires ops approval, amount unchanged.
```

**No model was needed.** A rule explained it. That is the desired outcome for the majority of
payments, and the system reports how often it achieves it.

## 5. Scope: minimal, but not trivial

**In scope.** UPI and card flows with a shared hop model. A live QR payment. Deterministic timeline,
expectation model and deviation detection. A rule catalogue. Admission control. Two model jobs
(synthesis and hypothesis) behind a hard process boundary. The grounding contract. Three audience
renderings. An evaluation harness over a golden set. A console that streams.

**Out of scope, deliberately.** Long-running workflow orchestration: an explanation is
request-scoped, so there is no Temporal-shaped problem here. Message brokers: a payment is a short
bounded hop sequence, not an unbounded stream, so Postgres is the queue. Object storage: there are
no blobs. Multi-tenancy, auth beyond a demo login, and horizontal scale.

**Four containers.** That is the complexity budget. It is enough to be a real distributed system
with a real service boundary and a real database, and small enough to start on a laptop in under a
minute.

---

# Part II: Architecture

## 6. Stack and layout

| Process | Stack | Why |
| --- | --- | --- |
| `pxe-api` | Java 21, Spring Boot 3.3 | The domain, the rules, the pipeline, SSE. One JVM. |
| `pxe-ai` | Python 3.12, FastAPI | Job A and Job B. **Holds no database credentials.** |
| `pxe-web` | Next.js 15, React 19, TypeScript | The console. |
| `postgres` | Postgres 16 | Everything durable, including the work queue. |

`pxe-ai` is a separate process for one reason: *"the model has no database connection"* is a real
security property, and only a process boundary proves it. A reviewer can verify it by reading the
compose file. That is worth one container.

```
pxe/
├── docker-compose.yml
├── api/
│   ├── src/main/java/com/pxe/
│   │   ├── ingest/          payment intake, QR intent, webhook
│   │   ├── model/           Payment, Hop, Reference, LedgerEntry, Explanation
│   │   ├── timeline/        L1 reconstruction
│   │   ├── expectation/     the expectation model, loaded from data
│   │   ├── deviation/       L2 detection
│   │   ├── rules/           the rule catalogue, L3a
│   │   ├── admission/       is this worth a model call
│   │   ├── explain/         orchestration, grounding validator, rendering
│   │   ├── eval/            golden-set harness
│   │   └── stream/          SSE endpoints
│   └── src/main/resources/db/migration/
├── ai/
│   ├── app/main.py          FastAPI, 4 routes
│   ├── app/jobs/            synthesis.py, hypothesis.py
│   ├── app/schemas.py       Pydantic, constrains model output
│   └── app/prompts/         versioned, hashed
├── web/
│   └── src/
│       ├── app/             pay, payment/[id], debt, eval
│       ├── components/      Timeline, HopRow, OutcomeTag, ExplanationPanel
│       └── lib/stream/      SSE client
└── data/
    ├── payment-scenarios.json    Appendix A of this document
    └── expectations.json         Section 9 of this document
```

## 7. The explanation pipeline

```
              payment lifecycle events
                       |
            [1] Normalization              canonical model, causal ordering
                       |
            [2] Timeline reconstruction    L1
                       |
            [3] Expectation lookup         what SHOULD have happened
                       |
            [4] Deviation detection        L2
                       |
            [5] Response-code resolution   L3a-i   <- most failures stop here
                       |
            [6] Rule catalogue             L3a-ii  <- most of the rest stop here
                       |
              unexplained deviation only
                       |
            [7] Admission control          worth a model call?
                       |
            [8] Hypothesis (Job B)         L3b
                       |
            [9] Grounding validator        G1-G8
                       |
           [10] Narrative (Job A)          L4
                       |
           [11] Audience renderer          merchant / support / engineer
                       |
           [12] Explanation record         durable, versioned, replayable
                       |
           [13] Action proposal            L5, policy-selected
```

Steps 2 to 6 are pure functions of the event set. Steps 8 and 10 are the only model calls, and both
run on the same small minority.

**The deterministic paths leave the pipeline after step 6 and re-enter at step 11.** `NONE`, `CODE`
and `RULE` never reach steps 7 to 10: their three audience renderings come from per-cause templates
keyed on the resolved root cause. Only `MODEL` and `ABSTAIN` run admission control, Job B and Job A.
That branch is what makes section 17.4 possible and what keeps the token counter at zero through
demo beat 7.

## 8. Data model

```sql
payments        id, merchant_id, amount_minor, currency, instrument, rail,
                tag, response_code, created_at, terminal_at,
                debt_open boolean, debt_opened_at, debt_closed_at

payment_hops    payment_id, seq, stage, actor, occurred_at, status, code,
                latency_ms, retry_of, duplicate_of, amount_minor, batch,
                cycle, cutoff_at, missed_cutoff, included, bound_reference,
                note, attrs jsonb

payment_references
                id, payment_id, hop_seq, kind, value, valid_from,
                superseded_by
                -- deliberately a TABLE, not a column on payments

ledger_entries  payment_id, kind, amount_minor, direction, posted_at

deviations      payment_id, type, detected_at, expected, actual, severity

explanations    id, payment_id, level, path, fact_set_hash, prompt_version,
                root_cause, determinable, confidence, hypothesis boolean,
                abstained boolean, claims jsonb, citations jsonb,
                merchant_text, support_text, engineer_text, generated_at

rule_hits       payment_id, rule_id, matched_at, inputs jsonb

model_calls     payment_id, job, admitted, priority, reason,
                prompt_tokens, completion_tokens, latency_ms,
                rejected_by, rejected_payload
```

**`payment_references` is a table and not a column.** Reference mutation across a retry is one of the
most common real causes of an unexplainable payment. A system storing one RRN per payment cannot
represent the problem, let alone explain it. This single decision is why scenario PXE-006 works.
Supersession is **within kind**: PXE-001 holds an RRN and a UTR that do not supersede each other,
PXE-006 holds two RRNs that do. The table is not named `references` because that is a reserved word
in Postgres.

**`occurred_at` is nullable and `attrs` is not decoration.** A hop with a null `occurred_at` is a hop
that did not happen, which is the absent node of section 19. `attrs` carries the low-frequency
per-stage fields the dataset records: `expiresAt`, `expectedBy`, `elapsedHours`, `slaHours`,
`lateBy`, `from`, `to`, `expectedMinor`, `actualMinor`, `deltaMinor`. Everything a rule in section 12
predicates on is a typed column, never a blob key.

**`debt_open` is on `payments`.** The debt queue is `WHERE debt_open = true ORDER BY amount_minor
DESC`. No separate table, no separate service.

## 9. The expectation model

Deviation requires an expectation, and expectation is **data, not code**. Ships as
`data/expectations.json`:

```json
[
  { "instrument": "UPI",  "stage": "SWITCH_REQUEST", "p50Ms": 400,  "p99Ms": 8000,  "slaMs": 30000,
    "nextStages": ["PAYER_DEBIT"] },
  { "instrument": "UPI",  "stage": "PAYER_DEBIT",    "p50Ms": 1200, "p99Ms": 9000,  "slaMs": 30000,
    "nextStages": ["PAYEE_CREDIT", "SWITCH_RETRY"] },
  { "instrument": "UPI",  "stage": "SETTLEMENT",     "cycle": "T+1", "creditByLocal": "18:00",
    "timezone": "UTC" },
  { "instrument": "CARD", "stage": "NETWORK_AUTH",   "p50Ms": 1200, "p99Ms": 6000,  "slaMs": 20000,
    "nextStages": ["CAPTURED"] },
  { "instrument": "CARD", "stage": "AUTH_TO_CAPTURE","slaMs": 604800000,
    "nextStages": ["CAPTURED"] },
  { "instrument": "CARD", "stage": "SETTLEMENT",     "cycle": "T+2", "creditByLocal": "18:00",
    "timezone": "UTC" },
  { "instrument": "NETBANKING", "stage": "CALLBACK",  "p99Ms": 45000, "slaMs": 900000,
    "nextStages": ["STATE_RECONCILED"] },
  { "instrument": "UPI",  "stage": "PAYOUT",          "slaMs": 21600000,
    "nextStages": ["PAYOUT_CREDITED"] }
]
```

A payment is **deviating** when it exceeds `slaMs` for its stage, skips a required `nextStage`,
enters a stage twice without a `retryOf` link, or ends in a state its instrument does not define.

**`creditByLocal` resolves in the row's own `timezone`, which is UTC**, matching the `cutoffAt`
recorded on the settlement hops in Appendix A. `T+N` counts from the date of the
`SETTLEMENT_SCHEDULED` hop. There is **no working-day calendar**: PXE-007 settles on a Sunday and
PXE-012 on a Saturday. Lateness is a subtraction against the deadline, not a business-day
computation. With these semantics exactly one scenario in the dataset is `SETTLED_LATE`, which is
what Appendix A declares.

## 9.1 The deviation catalogue

The four conditions above describe the **shapes** a deviation takes. They are not themselves
detectors, and a literal implementation of them is wrong: "skips a required `nextStage`" fires on
PXE-003, where a decline correctly has no next stage, and on PXE-009, which never records
`STATE_RECONCILED`. Neither declares a deviation. **This table is normative and is the only
producer of deviation rows.**

Every predicate is a pure function of the hop sequence, the reference rows and the expectation
model. Each is listed with the scenarios it must fire on, and it must fire on no others.

| Type | Fires when | Scenarios |
| --- | --- | --- |
| `NO_CUSTOMER_ACTION` | `INTENT_CREATED` and `INTENT_EXPIRED` both recorded, and no `QR_SCANNED`, `BANK_SESSION`, `PAYER_DEBIT` or `NETWORK_AUTH` hop exists | PXE-005 |
| `RRN_MUTATED` | two references of the same kind carry different values, so the earlier is superseded | PXE-006 |
| `UNSETTLED` | `SETTLEMENT_SCHEDULED` recorded and no `PAYOUT_CREDITED` has occurred | PXE-006 |
| `BATCH_EXCLUSION` | a hop records `included = false` | PXE-006 |
| `SETTLED_LATE` | `PAYOUT_CREDITED` occurred after the settlement deadline: `T+N` from the `SETTLEMENT_SCHEDULED` date at `creditByLocal` in the row's `timezone` | PXE-007 |
| `AMOUNT_MISMATCH` | at least two of the authorized, captured and settled amounts are recorded and they are not all equal | PXE-008, PXE-012 |
| `DUPLICATE_SUPPRESSED` | a hop carries `duplicateOf` | PXE-009 |
| `AUTO_REVERSAL_PENDING` | `AUTO_REVERSAL_INITIATED` recorded with status `PENDING` | PXE-010 |
| `UNEXPLAINED_TERMINAL` | a hop carries a response code classified as **not self-explaining** in section 11 | PXE-011 |
| `LEDGER_ASYMMETRY` | `PAYER_DEBIT` succeeded, no `PAYEE_CREDIT` succeeded, and no reversal was initiated | PXE-011 |
| `UNRECONCILED` | `RECON_BREAK_DETECTED` recorded with status `OPEN` | PXE-012 |
| `RETRY_CASCADE` | three or more hops share a stage and at least one carries `retryOf` | PXE-013 |
| `ABSENT_TERMINAL_EVENT` | a hop is recorded with status `ABSENT` | PXE-014 |
| `SLA_BREACHED` | a hop has not occurred, records an elapsed wait, and that wait exceeds the `slaMs` of the expectation row listing its stage in `nextStages` | PXE-014 |
| `LATE_CALLBACK` | `CALLBACK_RECEIVED` occurred after `SESSION_ABANDONED` | PXE-015 |
| `CUSTOMER_SAW_FAILURE` | `SESSION_ABANDONED` recorded and the payment later reconciled forward to `SUCCESS` | PXE-015 |

Three of these carry the weight and are worth stating plainly.

**`LEDGER_ASYMMETRY` requires that no reversal is running.** PXE-010 is also a debit without a
credit, and it does not deviate this way, because the network is already reversing it. The finding
is an *orphaned* debit, not an asymmetric one.

**`SLA_BREACHED` applies to absence, not to lateness.** An event that arrived late is
`LATE_CALLBACK`; an event that has not arrived at all and is past its SLA is `SLA_BREACHED`. The
elapsed wait is read from the record, because the system has no clock (see the open item in
section 9.2), which is also why PXE-011's missing credit does not breach: nothing recorded how long
it has been missing.

**`ABSENT` and `NOT_RECEIVED` are different statuses on purpose.** `ABSENT` means an expected event
has not arrived and its absence is itself the finding, which is PXE-014 and the absent node of
section 19. `NOT_RECEIVED` means an event will never arrive because the flow already failed
upstream, which is PXE-011's credit. Only `ABSENT` raises `ABSENT_TERMINAL_EVENT`.

## 9.2 Matching an expectation row to a hop

Expectation rows are keyed by `instrument` and `stage`, but only three of the eight `stage` values
are hop stages. Rows are matched three ways:

| Row | Matched by | Consumed by |
| --- | --- | --- |
| `SWITCH_REQUEST`, `PAYER_DEBIT`, `NETWORK_AUTH` | `stage` equals the hop stage | latency checks |
| `PAYOUT` | `nextStages` contains the stage of a hop that has not occurred | `SLA_BREACHED` |
| `SETTLEMENT` | looked up by instrument; it is a cycle, not a hop | `SETTLED_LATE` |
| `AUTH_TO_CAPTURE`, `CALLBACK` | spans, not stages | nothing yet |

`AUTH_TO_CAPTURE` and `CALLBACK` are unconsumed. `CALLBACK` in particular must **not** be evaluated
as a latency from redirect to callback: PXE-015 exceeds its 15-minute `slaMs` by design and declares
`LATE_CALLBACK`, not `SLA_BREACHED`.

**There is no clock.** PXE-014 records `elapsedHours: 71`, which is not derivable from any consistent
"now" across the dataset, and no other scenario records an elapsed wait. Detection therefore reads
elapsed time from the record and never from a wall clock. A live payment will need a configured
clock; a replayed one must not use it, or the golden set stops being reproducible.

## 10. Outcome tags

Every payment carries a terminal tag from a closed vocabulary. For a clean success it is the only
thing shown.

| Tag | Meaning | Debt |
| --- | --- | --- |
| `SUCCESS` | Debited, credited, confirmed | **None** |
| `PENDING` | In flight, inside SLA | None yet |
| `DEEMED_SUCCESS` | Debit confirmed, switch confirmation timed out | **Owed** |
| `DECLINED` | Explicitly refused by customer or issuer | **Owed** |
| `FAILED` | Technical failure at some hop | **Owed** |
| `EXPIRED` | Intent lapsed with no customer action | **Owed** |
| `REVERSED` | Debited then auto-reversed | **Owed** |

**A payment has two independent axes.** The tag is what the rails said. The deviation set is what
your expectation model says. They disagree more often than you would like:

```
SUCCESS,        []                ->  nothing owed
SUCCESS,        [UNRECONCILED]    ->  owed
SUCCESS,        [SETTLED_LATE]    ->  owed
DEEMED_SUCCESS, [RRN_MUTATED]     ->  owed
DECLINED,       []                ->  owed, and trivially explained
```

## 11. Response codes

The rail's own code is the cheapest explanation available and is consulted before anything else.

| Code | Commonly | Self-explaining? |
| --- | --- | --- |
| `Z9` | Insufficient funds | Yes, fully |
| `ZM` | Incorrect MPIN | Yes, fully |
| `ZA` | Declined by customer | Yes, fully |
| `U30` | Debit timeout, deemed approved | Partly. Says what, not what happens next. |
| `U28` / `BT` | Beneficiary bank unavailable or timed out | Partly |
| `91` | Issuer unavailable (card soft decline) | Partly |
| `96` | System malfunction | **No. This is the code that needs a model.** |

> **Verify this table against the current NPCI UDIR specification and your PSP's error documentation
> before relying on it.** These mappings are broadly correct and stable, but code semantics shift
> between circulars, and anyone from a payments company will know the live list better than any
> secondary source. Treat this as the shape of the mapping, not the authority for it.

The last column is the useful part. **Codes sort into self-explaining and not**, and only the second
group can justify a model call. That sort is a deterministic rule, and most of the cost saving lives
in it.

## 12. The rule catalogue

Eight rules cover the dataset. Each is a pure predicate over the hop sequence.

| Rule id | Fires when | Explains |
| --- | --- | --- |
| `RECON_RRN_MUTATION` | `retryOf` present, active reference differs from `boundReference`, batch closed with `included=false` | Settlement selector bound a superseded reference |
| `SETTLEMENT_CUTOFF_MISSED` | `missedCutoff=true` and payout delta exceeds cycle SLA | Rolled to the next cycle across non-working days |
| `AUTH_CAPTURE_DRIFT` | capture amount < auth amount | Partial capture, residual released by issuer |
| `IDEMPOTENT_DUPLICATE` | two hops, same stage, same reference, `duplicateOf` set | Duplicate suppressed before the ledger |
| `BENEFICIARY_DOWN_AUTOREVERSE` | debit OK, credit `U28`/`BT`, reversal initiated | Debit without credit, reversal running |
| `SOFT_DECLINE_RECOVERED` | terminal success preceded by soft-decline codes | Retry cascade succeeded |
| `LATE_CALLBACK_RECONCILED` | terminal state reached after abandonment timeout | Bank confirmed after we gave up |
| `INTENT_EXPIRED_UNUSED` | intent created, expiry fired, no scan hop | Nobody ever scanned it |

**A rule is added only after its scenario exists in the golden set.** That ordering is
non-negotiable and is rule 8 in section 20.

---

# Part III: The AI Contract

## 13. Two jobs, separated on purpose

Conflating these is the most common design error in this space.

| | **Job A: Synthesis** | **Job B: Hypothesis** |
| --- | --- | --- |
| Question | "Say this clearly." | "Why did this happen?" |
| Input | An established fact chain | A fact chain with an unexplained gap |
| Risk | Wording only | A claim about causality |
| Frequency | Only the `MODEL` and `ABSTAIN` paths | Only when no code and no rule fires |
| Output | Prose with placeholders | A candidate cause, marked, cited |
| Gate | Grounding validator | Grounding validator plus hypothesis rules |

Job A is nearly free and nearly safe. Job B is the expensive, dangerous, interesting one, and the
architecture keeps it rare and clearly labelled.

**Neither job runs on a deterministic path.** `NONE`, `CODE` and `RULE` render from per-cause
templates, so a payment explained by a response code or a rule costs nothing. Job A follows Job B on
exactly the payments where Job B ran, which is why `expected.modelCalls` in Appendix A counts **Job B
invocations**: it is the call that carries causal risk, and it is the one section 22's deterministic
coverage metric is counting the absence of.

## 14. The claim structure

The model does not emit prose. It emits claims, which are validated, and only then rendered.

```json
{
  "claims": [
    { "id": "c1", "kind": "FACT",
      "text": "The first attempt timed out at the switch.",
      "citations": ["hop:4"] },

    { "id": "c2", "kind": "FACT",
      "text": "The retry succeeded under reference {ref_1}.",
      "citations": ["hop:6", "ref:445100000812"],
      "placeholders": { "ref_1": "references[1].value" } },

    { "id": "c3", "kind": "HYPOTHESIS", "confidence": 0.82,
      "text": "The settlement selector skipped it because it binds the original reference.",
      "citations": ["hop:8", "rule:RECON_RRN_MUTATION"] }
  ]
}
```

## 15. The grounding contract

| # | Rule | On failure |
| --- | --- | --- |
| **G1** | Every citation resolves to a real hop, ledger row, reference or rule id | Drop the claim |
| **G2** | Every cited record belongs to **this** payment or its merchant | Drop the claim |
| **G3** | Every placeholder resolves to a typed field on a cited record | Drop the claim |
| **G4** | **No amount, timestamp or reference appears as a literal in `text`** | **Reject the whole response** |
| **G5** | A `FACT` claim must be entailed by its citations, not merely adjacent | Downgrade to `HYPOTHESIS` |
| **G6** | A `HYPOTHESIS` must cite a rule id or at least two hops | Drop the claim |
| **G7** | The explanation may not assert a terminal status the ledger contradicts | Reject the response |
| **G8** | If every `FACT` claim is dropped, do not render L4 | Fall back to L2 |
| **G9** | `root_cause` is a member of `data/causes.json`, or the response abstains | Reject the whole response |

**G4 is the one to dwell on.** The model is structurally prevented from typing a number. Any literal
digit sequence resembling an amount, timestamp or reference is a protocol violation, not a content
error, and fails the entire response. Numbers arrive only through placeholder substitution from
typed fields after generation.

> A model that cannot type a number cannot mistype one.

**G9. The root cause is drawn from a closed vocabulary.** `data/causes.json` is the taxonomy of
causes this system can name, and the response schema constrains the model to it. A cause outside
the list is not a content error either; there is no field it can be written into.

This exists because section 22 scores an explanation by whether it **names** the injected cause,
and a name is only checkable against a fixed vocabulary. Left free, a model reading the same record
correctly will write `SWITCH_SYSTEM_MALFUNCTION_POST_DEBIT` where the dataset says
`SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK`, and score zero. That is a string coincidence being
reported as a false attribution, which makes the most important metric in the system meaningless in
both directions: it cannot distinguish a model that read the record wrongly from one that read it
correctly and chose a synonym.

The vocabulary is a taxonomy and not an answer key. It holds every cause the golden set injects
alongside causes it does not, so naming the right one is a judgement rather than a coin flip, and
adding a scenario does not mean adding its answer to a list of two. `UNDETERMINED` is always
available and is not a cause: it sets `determinable` to false and routes to the abstention path.

> A model that can only choose from a list can be scored against the list.

## 16. Determinism

The same payment in the same state produces the same explanation. Achieved by: temperature 0,
structured output, a content-addressed cache keyed on `fact_set_hash`, and a frozen `prompt_version`
recorded on every explanation. An explanation whose inputs have not changed is never regenerated.

Without this the system cannot be tested, and an explanation that cannot be reproduced is not
evidence.

---

# Part IV: The Console

## 17. Responsiveness is an architecture, not a polish pass

The console must feel instant. That comes from six decisions made at design time, not from
optimising later.

### 17.1 Never show a spinner. The wait is the content.

A UPI payment genuinely takes six to nine seconds. Do not hide that behind a loader. **Render the
hops arriving.** Progress through a real process is more interesting than a spinner and it makes
latency legible instead of frustrating. This is the single biggest perceived-speed decision in the
product.

### 17.2 Stream hops over SSE, append-only

`GET /api/payments/{id}/stream` emits one frame per hop. The client **appends**; it never refetches
and never re-renders the list. Each `HopRow` is memoised on `seq`, so arrival is O(1) DOM work.

```
event: hop
data: {"seq":4,"stage":"PAYER_DEBIT","status":"TIMEOUT","code":"U30","latencyMs":9000}
```

### 17.3 Pre-reserve the layout

**Render the full hop skeleton at intent creation**, greyed, and light each row as its event lands.
Nothing shifts, nothing reflows, and the user can see what is still to come. Cumulative layout shift
is zero by construction rather than by measurement.

The skeleton is **per rail, not per instrument**, and it does not come from `expectations.json`:
that file is keyed by instrument and sparse, and `UPI_QR`, `UPI_COLLECT` and `UPI_PAYOUT` share an
instrument while having nothing else in common. It ships as `data/rail-sequences.json`:

```json
{
  "UPI_QR":        ["INTENT_CREATED","QR_SCANNED","SWITCH_REQUEST","PAYER_DEBIT","PAYEE_CREDIT","SETTLEMENT_SCHEDULED","PAYOUT_CREDITED"],
  "UPI_COLLECT":   ["COLLECT_SENT","PAYER_DEBIT","PAYEE_CREDIT","SETTLEMENT_SCHEDULED","PAYOUT_CREDITED"],
  "UPI_PAYOUT":    ["PAYOUT_INSTRUCTED","PAYOUT_ACKNOWLEDGED","PAYOUT_CREDITED"],
  "CARD_DOMESTIC": ["AUTH_REQUEST","RISK_EVALUATED","NETWORK_AUTH","CAPTURED","SETTLEMENT_SCHEDULED","PAYOUT_CREDITED"],
  "NETBANKING":    ["REDIRECT_ISSUED","BANK_SESSION","CALLBACK_RECEIVED","STATE_RECONCILED","SETTLEMENT_SCHEDULED","PAYOUT_CREDITED"]
}
```

It is the **happy path**, not the union of everything a rail can emit. Two things follow, and both
are features rather than gaps:

**A skeleton row that never lights is the absent node.** PXE-014's `PAYOUT_CREDITED` is drawn from
the first frame and stays unlit, which is section 19 falling out of the layout decision rather than
being drawn specially.

**An unplanned event appends.** A retry, a reversal, a batch closure and a reconciliation break are
not on any happy path, so they arrive as new rows at the end. The timeline is therefore the last
block on the page and the explanation panel sits above it, so an append moves nothing that is
already on screen and the shift stays zero.

### 17.4 Precompute the deterministic explanation

This is the important one, and it falls out of the architecture for free.

For the `NONE`, `CODE` and `RULE` paths, which is **80% of payments**, the explanation is a pure
function of the events. So compute it the instant the outcome lands, before anyone asks. The
explanation is already in the SSE stream by the time the user clicks "why".

**Reveal latency for four out of five payments is zero.** Not fast. Zero.

### 17.5 Token-stream only the model path

The remaining 20% has real latency. Stream Job A's output token by token. A three-second generation
that starts producing text in 200ms feels faster than a 900ms one that appears all at once.

### 17.6 Animate on the compositor only

`transform` and `opacity`. Never `width`, `height`, `top` or `left`. Hop rows enter with
`translateY(-4px) -> 0` plus an opacity fade over 140ms. The debt and token counters use a spring
with digit-level tick animation, because a number that visibly moves is read as live and a number
that jumps is read as a refresh.

### 17.7 Budgets, enforced in CI

| Metric | Budget |
| --- | --- |
| Input to first visual feedback | < 100 ms |
| Frame budget while hops stream | < 16.6 ms |
| Cumulative layout shift | 0 |
| Reveal of a deterministic explanation | 0 ms, already present |
| First token of a model explanation | < 400 ms |

## 18. The screens

Four. No more.

**`/pay`.** A QR, large. The two persistent widgets. Nothing else.

```
  ┌───────────────┐
  │               │      EXPLANATION DEBT   0
  │   [ QR code ] │      TOKENS SPENT       0
  │               │
  └───────────────┘
```

**`/payment/[id]`.** The hop timeline, the outcome tag, and the explanation panel when a debt exists.
An audience switcher: merchant, support, engineer. Same fact set, three renderings.

**`/debt`.** The queue. Open debts sorted by exposure, with age against SLA. This is the ops screen
and the one that makes the debt idea concrete.

**`/eval`.** The harness. Run the golden set, show cause accuracy, false attribution, deterministic
coverage and cost per explained payment.

## 19. The absent node

Absence is a first-class visual element, not a blank row. When an expected hop does not arrive, the
timeline draws it:

```
  ●  PAYOUT_INSTRUCTED      11:02:00   ok
  ●  PAYOUT_ACKNOWLEDGED    11:02:04   ok
  ○  PAYOUT_CREDITED        ABSENT     71h elapsed / 6h SLA
     └─ cause not determinable from available evidence
```

Naming the missing thing precisely **is** the answer. Most systems cannot render this at all,
because a thing that did not happen emits no event, and they only draw events.

---

# Part V: Building It

## 20. Build order

Ten days, one developer. Each phase has an observable exit criterion.

| Phase | Days | Work | Exit criterion |
| --- | --- | --- | --- |
| **0. Skeleton** | 0.5 | Four containers, Flyway V1, health checks | `docker compose up` gives 4 healthy containers in under 60s |
| **1. Model and loader** | 1.0 | Schema, entities, load `payment-scenarios.json` | All 15 scenarios load; hop counts and references match the file |
| **2. Timeline and expectations** | 1.0 | L1 reconstruction, expectation lookup, L2 deviation | Every scenario's computed deviation set equals its declared `expected.deviations` |
| **3. Eval harness** | 1.0 | Golden-set runner, metrics, `/eval` screen | Harness runs and reports; every metric is 0 because nothing explains yet |
| **4. Code and rule paths** | 1.5 | Response-code resolution, 8 rules, debt open/close | Deterministic coverage **80%**; the 12 non-MODEL scenarios explain with **zero** model calls |
| **5. Console and stream** | 1.5 | SSE, timeline, tags, pre-reserved layout, precomputed reveal | A payment streams hop by hop; CLS is 0; deterministic explanation reveal is instant |
| **6. AI service** | 1.5 | FastAPI, Job A, Job B, structured output, no DB creds | `grep -r DATABASE_URL ai/` returns nothing; PXE-011 and PXE-012 produce hypotheses |
| **7. Grounding validator** | 1.0 | G1 to G8, placeholder substitution, rejection log | A deliberately malformed response with a literal amount is **rejected by G4** and logged |
| **8. Abstention and renderings** | 1.0 | Abstention path, three audience renderers, absent node | PXE-014 abstains rather than attributing; absent node renders |

**Build the eval harness (phase 3) before anything that explains.** If it exists, every later
decision is measurable. If it does not, you will tune prompts by feel and have nothing to show.

## 21. Non-negotiable rules

1. The model may not establish a fact. It renders facts or proposes clearly-marked hypotheses.
2. The model may not type a number. Numbers are substituted from typed fields after generation.
3. An uncited claim is removed, not softened.
4. The AI service holds no database credentials. Enforced by process boundary, not policy.
5. Recommended actions are selected by policy. The model does not choose them.
6. "Cannot be determined" is always available and is preferred to a plausible guess.
7. Every explanation records its `fact_set_hash` and `prompt_version`, or it cannot be reproduced.
8. **A new failure mode enters the golden set before the rule that explains it is written.**

## 22. Evaluation

The simulator injects known failures, so **ground truth is known**. If the generator injects
`RRN_MUTATION_ON_RETRY` into payment P, the correct explanation of P names RRN mutation. That is
mechanically checkable, and it converts explainability from a subjective quality into a measured
one.

| Metric | Definition | Target | Enforced or measured |
| --- | --- | --- | --- |
| Groundedness | Claims with resolving citations | 100% | Enforced, G1 to G3 |
| Numeric fidelity | Rendered numbers matching the ledger | 100% | Enforced, G4 |
| Cause accuracy | Explanations naming the injected root cause | > 85% | Measured |
| **False attribution** | Explanations naming a **wrong** cause confidently | **< 2%** | Measured |
| Abstention correctness | "Cannot determine" when genuinely undeterminable | > 90% | Measured |
| Determinism | Same fact set, same explanation hash | 100% | Enforced by cache |
| Deterministic coverage | Payments resolved with no model call, over **all** payments | >= 80% | Measured |
| Cost per explained payment | Tokens divided by payments explained | Falling | Measured |

**The deterministic-coverage denominator is every payment, not every explained payment.** The two
clean successes count as deterministically resolved: they needed no model and they owe nothing.
That is what makes the dataset's 12/15 arithmetic work, and it is why the target is `>=` rather than
`>`, since the dataset lands on exactly 0.8 and must pass its own bar.

**A metric with an empty denominator reports zero, and reports the denominator too.** Before
anything explains, groundedness, numeric fidelity, determinism, false attribution and cost are all
`0/0`. A zero that means "nothing yet" and a zero that means "failing" are different facts, and the
harness must not conflate them.

## 22.1 The ambiguity set

The golden set measures reach: how much of ordinary traffic the funnel explains, how cheaply, and
how often it names the injected cause. It cannot measure honesty, because almost nothing in it is
genuinely undeterminable and a metric with a denominator of one is not a metric.

`data/ambiguity-set.json` is the second corpus. Every case in it is built so that a plausible answer
is available and wrong: the record supports two or more mechanisms and contains nothing that
separates them. Each is a near-miss on a case the catalogue explains confidently, differing by a
single field.

| Case | Differs from | By |
| --- | --- | --- |
| `AMB-001` | PXE-009, a suppressed duplicate | no duplicate flag, and the two references differ |
| `AMB-002` | PXE-008, a partial capture | the capture exceeds the authorization instead |
| `AMB-003` | PXE-011, an orphaned debit | no response code at all, so nothing says where it broke |
| `AMB-004` | PXE-006, a batch exclusion | the batch reports the payment **included** |

**It is scored for abstention correctness and false attribution, and kept out of the coverage
denominator.** Production traffic is not one fifth undeterminable. Folding these into the golden set
would drag deterministic coverage from 80% to 63% and make a true claim about the funnel read as a
false one, which is the wrong trade for a number that is otherwise honest.

**Adding cases here should make the numbers worse.** That is what the set is for. A corpus of
adversarial cases that everything passes is a corpus that was written after the answers.

**False attribution is the metric that matters most and the one nobody reports.** A system that
explains 95% correctly and confidently misattributes 5% is worse in operations than one that
explains 85% and abstains on the rest, because misattributions get acted upon. The target is
deliberately asymmetric.

## 23. The demo

**One QR on screen. One phone. You are the merchant, and you pay yourself.**

Two widgets stay visible the entire time and are never hidden. They are the argument.

```
  EXPLANATION DEBT: 0        TOKENS SPENT: 0
```

### Wiring the QR

| Option | Cost | Buys |
| --- | --- | --- |
| **A PSP's test mode** | An account, a test key, an afternoon | You demo a payments product through a payments company's own API. Test-mode UPI identities force specific outcomes, so failure is deterministic on stage. |
| Simulated QR | Nothing | The QR encodes an intent that hits your own backend. Fully controllable, but the rails are visibly yours. |

> Confirm the current test-mode VPA strings in the provider's live documentation. Forced-outcome test
> identities exist and are stable, but the exact strings are theirs to change and a hardcoded wrong
> one fails on stage.

A scenario selector decides what the next scan does. **Say that out loud.** *"I am choosing which
failure the rails return, because I cannot make a real bank time out on cue"* is a credible
sentence. Pretending otherwise is not.

### Act 1, nothing is owed (2 min)

| # | Happens | Shows |
| --- | --- | --- |
| 1 | Scan, pay 500. Hops land live. | The journey is data, not a status column. |
| 2 | Tag: **`SUCCESS`**. Timeline, latencies, settlement date. Nothing else. | **No explanation, no narrative, no model.** |
| 3 | Point at the widgets. `DEBT 0`, `TOKENS 0`. | "A payment that worked owes you nothing, and it cost nothing to say so." |

Resist making act 1 impressive. Its restraint is what makes act 2 land.

### Act 2, a debt is incurred (4 min)

| # | Happens | Shows |
| --- | --- | --- |
| 4 | Tag **`DECLINED`, `Z9`**. Explanation appears instantly. | Debt 1 to 0, `TOKENS` **still 0**. A real failure needing no model. Pre-empts "so you just call an LLM". |
| 5 | Tag **`DEEMED_SUCCESS`, `U30`**. Stalls unsettled. | Deviation against the expectation model. Debt goes to 1 and stays. |
| 6 | Ask why. Answer returns from **rule `RECON_RRN_MUTATION`**. | `TOKENS` still 0. Show the rule that fired. |
| 7 | Switch audience: merchant, support, engineer. | One fact set, three renderings. |

### Act 3, the model earns its call (4 min)

| # | Happens | Shows |
| --- | --- | --- |
| 8 | Tag **`FAILED`, `96`**, no rule matches. Model invoked. | `TOKENS` moves off zero **for the first time, at minute seven**. That transition is the funnel argument, shown rather than claimed. |
| 9 | Answer is a **hypothesis**, labelled, cited, confidence 0.78. | Not presented as fact, because it is not one. |
| 10 | A rejected response: the model typed `18,400` as a literal. **G4 fires, whole response discarded.** | **The beat that proves the safety claim.** A structural rule firing, with the rejected payload on screen. |
| 11 | Beneficiary silence. | "Cannot be determined, and here is exactly what is missing." The absent node renders. |
| 12 | Run the eval harness live. | Cause accuracy, **false attribution**, deterministic coverage, cost. |

### What to rehearse hardest

**Beat 10**, because a safety rule visibly catching the model is worth more than any number of
correct outputs. **Beat 12**, because it is the only moment proving quality is measured rather than
asserted. And **beat 3**, the sleeper: a room that has seen thirty AI demos this year has never seen
one deliberately produce no AI output. Doing it on purpose, pointing at a counter reading zero, says
more about engineering judgement than the rest of the demo combined.

## 24. Definition

> A platform that observes every hop a payment makes, explains what happened to it in language
> matched to whoever is asking, and is architected so that the model doing the explaining is never
> the thing deciding what is true.

## 25. Thesis

The interesting problem in applied AI is not getting a model to produce a good answer. It is
building the system that can tell whether the answer is good, cheaply, at volume, and before a human
sees it.

Everything here follows from that: the ladder exists so the model receives established facts rather
than raw data; the citation contract exists so groundedness is enforced rather than hoped for; the
placeholder mechanism exists so the model cannot mistype money; and the golden set exists so
explanation quality is a number that moves rather than an impression.

An explanation that cannot be checked is not an explanation. It is a plausible sentence about
someone's money.

---

# Appendix A: The dataset

Copy this block verbatim to `data/payment-scenarios.json`. Fifteen scenarios: 2 clean successes,
2 explained by response code, 8 by rule, 2 needing a model, 1 requiring abstention.
**Deterministic coverage is 12/15 = 80%**, which is the target in section 22 and is not a
coincidence: the dataset was built to make the funnel claim testable.

`injectedCause` is ground truth and is what a correct explanation must name. `expected.path` records
how the explanation should be produced, which makes the funnel testable: **if a scenario marked
`CODE` ever reaches the model, the funnel has regressed.**

```json
{
  "version": "1.0.0",
  "anchorDate": "2026-03-08T00:00:00Z",
  "paths": {
    "NONE": "Success with no deviation. No explanation owed, no cost.",
    "CODE": "The rail's own response code fully explains it. Deterministic, no model.",
    "RULE": "A rule in the catalogue attributes the cause. Deterministic, no model.",
    "MODEL": "No rule fires. Admission control decides whether to spend a model call.",
    "ABSTAIN": "Reached the model, and the correct output is that the cause cannot be determined."
  },
  "coverageTargets": {
    "deterministicCoverage": 0.8,
    "causeAccuracy": 0.85,
    "falseAttribution": 0.02,
    "abstentionCorrectness": 0.9
  },
  "scenarios": [
    {
      "id": "PXE-001",
      "name": "clean-upi-success",
      "title": "Clean UPI QR payment",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 50000,
      "currency": "INR",
      "injectedCause": null,
      "expected": {
        "tag": "SUCCESS", "responseCode": "00", "deviations": [], "debt": 0,
        "explanationRequired": false, "path": "NONE", "modelCalls": 0, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T10:00:00.100Z", "status": "OK", "latencyMs": 12 },
        { "seq": 2, "stage": "QR_SCANNED", "actor": "CUSTOMER_PSP", "at": "2026-03-08T10:00:06.400Z", "status": "OK", "latencyMs": 6300 },
        { "seq": 3, "stage": "SWITCH_REQUEST", "actor": "NPCI", "at": "2026-03-08T10:00:06.900Z", "status": "OK", "latencyMs": 500 },
        { "seq": 4, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T10:00:08.100Z", "status": "OK", "code": "00", "latencyMs": 1200 },
        { "seq": 5, "stage": "PAYEE_CREDIT", "actor": "PAYEE_BANK", "at": "2026-03-08T10:00:08.900Z", "status": "OK", "code": "00", "latencyMs": 800, "reference": { "kind": "RRN", "value": "445100000001" } },
        { "seq": 6, "stage": "SETTLEMENT_SCHEDULED", "actor": "PXE", "at": "2026-03-08T10:00:09.100Z", "status": "OK", "batch": "SB-4401", "cycle": "T+1" },
        { "seq": 7, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-09T14:02:00.000Z", "status": "OK", "reference": { "kind": "UTR", "value": "UTR2603090001" } }
      ],
      "explanation": null,
      "demoNote": "Act 1. A tag, a timeline, a settlement date, nothing else. Token counter stays at zero."
    },
    {
      "id": "PXE-002",
      "name": "clean-card-success",
      "title": "Clean card payment, auth then capture",
      "instrument": "CARD",
      "rail": "CARD_DOMESTIC",
      "amountMinor": 249900,
      "currency": "INR",
      "injectedCause": null,
      "expected": {
        "tag": "SUCCESS", "responseCode": "00", "deviations": [], "debt": 0,
        "explanationRequired": false, "path": "NONE", "modelCalls": 0, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "AUTH_REQUEST", "actor": "PXE", "at": "2026-03-08T11:15:00.000Z", "status": "OK", "latencyMs": 20 },
        { "seq": 2, "stage": "RISK_EVALUATED", "actor": "PXE", "at": "2026-03-08T11:15:00.180Z", "status": "PASS", "latencyMs": 180 },
        { "seq": 3, "stage": "NETWORK_AUTH", "actor": "NETWORK", "at": "2026-03-08T11:15:01.400Z", "status": "OK", "code": "00", "latencyMs": 1220, "reference": { "kind": "ARN", "value": "ARN74512603080011" } },
        { "seq": 4, "stage": "CAPTURED", "actor": "PXE", "at": "2026-03-08T11:15:02.000Z", "status": "OK", "amountMinor": 249900 },
        { "seq": 5, "stage": "SETTLEMENT_SCHEDULED", "actor": "PXE", "at": "2026-03-08T11:15:02.200Z", "status": "OK", "batch": "SB-4402", "cycle": "T+2" },
        { "seq": 6, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-10T14:02:00.000Z", "status": "OK", "reference": { "kind": "UTR", "value": "UTR2603100007" } }
      ],
      "explanation": null,
      "demoNote": "Shows the expectation model is per-instrument: cards settle T+2, UPI T+1, neither hardcoded."
    },
    {
      "id": "PXE-003",
      "name": "insufficient-funds",
      "title": "Declined, insufficient funds",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 1200000,
      "currency": "INR",
      "injectedCause": "INSUFFICIENT_FUNDS",
      "expected": {
        "tag": "DECLINED", "responseCode": "Z9", "deviations": [], "debt": 1,
        "explanationRequired": true, "path": "CODE", "modelCalls": 0, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T12:00:00.100Z", "status": "OK", "latencyMs": 11 },
        { "seq": 2, "stage": "QR_SCANNED", "actor": "CUSTOMER_PSP", "at": "2026-03-08T12:00:05.000Z", "status": "OK", "latencyMs": 4900 },
        { "seq": 3, "stage": "SWITCH_REQUEST", "actor": "NPCI", "at": "2026-03-08T12:00:05.400Z", "status": "OK", "latencyMs": 400 },
        { "seq": 4, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T12:00:06.200Z", "status": "DECLINED", "code": "Z9", "latencyMs": 800 }
      ],
      "explanation": {
        "rootCause": "INSUFFICIENT_FUNDS",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:4", "code:Z9"],
        "merchant": "The payment was declined by the customer's bank because the account did not have enough balance. No money left the customer's account and nothing is owed to you for this attempt. The customer can retry.",
        "support": "Declined at the payer bank with Z9, insufficient funds. No debit occurred, so there is nothing to reverse and no reconciliation follow-up. Advise the customer to retry with sufficient balance or another account. No escalation.",
        "engineer": "PAYER_DEBIT returned Z9 at hop 4 after 800ms. Terminal at the payer bank, no switch retry attempted, no RRN issued. Ledger has no entry. Resolved from the response code alone, zero model calls."
      },
      "demoNote": "Beat 4, the anti-LLM beat. A genuine failure that owes an explanation and needs no model."
    },
    {
      "id": "PXE-004",
      "name": "wrong-mpin",
      "title": "Declined, incorrect MPIN",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 75000,
      "currency": "INR",
      "injectedCause": "INCORRECT_MPIN",
      "expected": {
        "tag": "DECLINED", "responseCode": "ZM", "deviations": [], "debt": 1,
        "explanationRequired": true, "path": "CODE", "modelCalls": 0, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T12:30:00.100Z", "status": "OK", "latencyMs": 10 },
        { "seq": 2, "stage": "QR_SCANNED", "actor": "CUSTOMER_PSP", "at": "2026-03-08T12:30:08.000Z", "status": "OK", "latencyMs": 7900 },
        { "seq": 3, "stage": "SWITCH_REQUEST", "actor": "NPCI", "at": "2026-03-08T12:30:08.300Z", "status": "OK", "latencyMs": 300 },
        { "seq": 4, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T12:30:09.100Z", "status": "DECLINED", "code": "ZM", "latencyMs": 800 }
      ],
      "explanation": {
        "rootCause": "INCORRECT_MPIN",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:4", "code:ZM"],
        "merchant": "The customer entered an incorrect UPI PIN, so their bank declined the payment. Nothing was debited. They can try again.",
        "support": "ZM at the payer bank, incorrect MPIN. Customer-side and self-correcting. Repeated ZM means they should reset their UPI PIN in their payment app. No action on our side.",
        "engineer": "PAYER_DEBIT returned ZM at hop 4. Authentication failure at the issuer, terminal, no debit. Resolved from the response code, zero model calls."
      },
      "demoNote": "A second CODE-path scenario, so the self-explaining class reads as a class and not one lucky case."
    },
    {
      "id": "PXE-005",
      "name": "qr-expired",
      "title": "QR expired with no customer action",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 30000,
      "currency": "INR",
      "injectedCause": "INTENT_EXPIRED_NO_ACTION",
      "expected": {
        "tag": "EXPIRED", "responseCode": null, "deviations": ["NO_CUSTOMER_ACTION"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "INTENT_EXPIRED_UNUSED"
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T13:00:00.100Z", "status": "OK", "latencyMs": 10, "expiresAt": "2026-03-08T13:05:00.000Z" },
        { "seq": 2, "stage": "INTENT_EXPIRED", "actor": "PXE", "at": "2026-03-08T13:05:00.000Z", "status": "EXPIRED" }
      ],
      "explanation": {
        "rootCause": "INTENT_EXPIRED_NO_ACTION",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:1", "hop:2"],
        "merchant": "The QR code was never scanned and expired after five minutes. No payment was attempted and no money moved.",
        "support": "Intent expired unused. There is no customer-side failure to explain because no customer ever engaged with it. Generate a fresh QR if they are still present.",
        "engineer": "INTENT_CREATED at 13:00:00.100 with expiresAt 13:05:00. No QR_SCANNED hop recorded. Expiry is our own timer firing, not a rail response. Zero model calls."
      },
      "demoNote": "The cheapest possible explanation of an absence. Contrast with PXE-014, where absence is genuinely undeterminable."
    },
    {
      "id": "PXE-006",
      "name": "deemed-success-rrn-mutation",
      "title": "Deemed success, retry succeeded under a new reference",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 1840000,
      "currency": "INR",
      "injectedCause": "RRN_MUTATION_ON_RETRY",
      "expected": {
        "tag": "DEEMED_SUCCESS", "responseCode": "U30",
        "deviations": ["RRN_MUTATED", "UNSETTLED", "BATCH_EXCLUSION"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "RECON_RRN_MUTATION"
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T10:00:03.000Z", "status": "OK", "latencyMs": 14 },
        { "seq": 2, "stage": "RISK_EVALUATED", "actor": "PXE", "at": "2026-03-08T10:00:04.000Z", "status": "PASS", "latencyMs": 210 },
        { "seq": 3, "stage": "SWITCH_REQUEST", "actor": "NPCI", "at": "2026-03-08T10:00:05.000Z", "status": "OK", "latencyMs": 400 },
        { "seq": 4, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T10:00:14.000Z", "status": "TIMEOUT", "code": "U30", "latencyMs": 9000, "reference": { "kind": "RRN", "value": "445100000811" } },
        { "seq": 5, "stage": "SWITCH_RETRY", "actor": "NPCI", "at": "2026-03-08T10:00:44.000Z", "status": "OK", "latencyMs": 300, "retryOf": 3 },
        { "seq": 6, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T10:00:45.100Z", "status": "OK", "code": "00", "latencyMs": 1100, "retryOf": 4, "reference": { "kind": "RRN", "value": "445100000812" } },
        { "seq": 7, "stage": "PAYEE_CREDIT", "actor": "PAYEE_BANK", "at": "2026-03-08T10:00:45.900Z", "status": "OK", "code": "00", "latencyMs": 800 },
        { "seq": 8, "stage": "SETTLEMENT_SCHEDULED", "actor": "PXE", "at": "2026-03-08T10:00:46.000Z", "status": "OK", "batch": "SB-9931", "cycle": "T+1", "boundReference": "445100000811" },
        { "seq": 9, "stage": "BATCH_CLOSED", "actor": "PXE", "at": "2026-03-09T18:00:00.000Z", "status": "CLOSED", "batch": "SB-9931", "included": false }
      ],
      "explanation": {
        "rootCause": "RRN_MUTATION_ON_RETRY",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:4", "hop:6", "hop:8", "hop:9", "rule:RECON_RRN_MUTATION"],
        "merchant": "Your money is not lost. The first attempt timed out at the payment network and the automatic retry succeeded, but under a new reference number. Our settlement run picks payments up by their original reference, so this one was skipped rather than rejected. It is captured, it is owed to you, and it will be released in the next cycle once the reference is re-bound.",
        "support": "Deemed success with U30 on attempt 1, retry succeeded under RRN 445100000812. Settlement batch SB-9931 binds the original RRN 445100000811 and excluded it at close. The merchant is owed the full amount. Action REBIND_RRN_AND_REQUEUE requires ops approval. Tell the merchant the funds are safe and give the next cycle date, not an apology for a failure, because it did not fail.",
        "engineer": "U30 at hop 4 after a 9s payer-bank timeout, RRN 445100000811 issued. NPCI retry at hop 5 produced a successful debit at hop 6 under RRN 445100000812. PAYEE_CREDIT confirmed. SETTLEMENT_SCHEDULED at hop 8 recorded boundReference 445100000811, the pre-retry value. BATCH_CLOSED at hop 9 with included=false. Root cause: the settlement selector binds the reference captured at schedule time rather than the payment's current active reference. Rule RECON_RRN_MUTATION matched on (retryOf present, reference differs, boundReference equals superseded value)."
      },
      "demoNote": "Beats 5 to 7, the centrepiece. A three-day stall fully explained with the token counter still at zero."
    },
    {
      "id": "PXE-007",
      "name": "settled-late-cutoff-missed",
      "title": "Succeeded, but settled nine days late",
      "instrument": "UPI",
      "rail": "UPI_COLLECT",
      "amountMinor": 620000,
      "currency": "INR",
      "injectedCause": "BANK_CUTOFF_MISSED_WEEKEND",
      "expected": {
        "tag": "SUCCESS", "responseCode": "00", "deviations": ["SETTLED_LATE"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "SETTLEMENT_CUTOFF_MISSED"
      },
      "hops": [
        { "seq": 1, "stage": "COLLECT_SENT", "actor": "PXE", "at": "2026-03-06T17:58:00.000Z", "status": "OK" },
        { "seq": 2, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-06T17:59:40.000Z", "status": "OK", "code": "00", "reference": { "kind": "RRN", "value": "445100000920" } },
        { "seq": 3, "stage": "PAYEE_CREDIT", "actor": "PAYEE_BANK", "at": "2026-03-06T17:59:55.000Z", "status": "OK", "code": "00" },
        { "seq": 4, "stage": "SETTLEMENT_SCHEDULED", "actor": "PXE", "at": "2026-03-06T18:00:12.000Z", "status": "OK", "batch": "SB-9800", "cycle": "T+1", "cutoffAt": "2026-03-06T18:00:00.000Z", "missedCutoff": true },
        { "seq": 5, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-15T14:02:00.000Z", "status": "OK", "reference": { "kind": "UTR", "value": "UTR2603150014" } }
      ],
      "explanation": {
        "rootCause": "BANK_CUTOFF_MISSED_WEEKEND",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:4", "hop:5", "rule:SETTLEMENT_CUTOFF_MISSED"],
        "merchant": "The payment succeeded on 6 March but landed twelve seconds after that day's settlement cut-off, so it rolled into the next cycle. That cycle fell across a weekend and a bank holiday, which is why it reached you on 15 March rather than 9 March. Nothing failed and the full amount was paid.",
        "support": "Settled late by 6 working days. Captured 17:59:55, scheduled 18:00:12, cut-off 18:00:00, missed by 12 seconds and rolled across a non-working stretch. Legitimate and worth flagging proactively rather than waiting for the merchant to ask.",
        "engineer": "SETTLEMENT_SCHEDULED at hop 4 recorded missedCutoff=true, scheduled 12s past cutoffAt. Next eligible cycle was the following working day. Rule SETTLEMENT_CUTOFF_MISSED matched on (missedCutoff true, payout delta exceeds cycle SLA). Note this is a SUCCESS tag carrying a debt: the rails are content and the expectation model is not."
      },
      "demoNote": "The scenario proving the two-axis model. Tag is SUCCESS and it still owes an explanation."
    },
    {
      "id": "PXE-008",
      "name": "partial-capture-drift",
      "title": "Authorized 12,000, captured 9,400",
      "instrument": "CARD",
      "rail": "CARD_DOMESTIC",
      "amountMinor": 1200000,
      "currency": "INR",
      "injectedCause": "PARTIAL_CAPTURE_DRIFT",
      "expected": {
        "tag": "SUCCESS", "responseCode": "00", "deviations": ["AMOUNT_MISMATCH"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "AUTH_CAPTURE_DRIFT"
      },
      "hops": [
        { "seq": 1, "stage": "AUTH_REQUEST", "actor": "PXE", "at": "2026-03-07T09:00:00.000Z", "status": "OK", "amountMinor": 1200000 },
        { "seq": 2, "stage": "NETWORK_AUTH", "actor": "NETWORK", "at": "2026-03-07T09:00:01.300Z", "status": "OK", "code": "00", "amountMinor": 1200000, "reference": { "kind": "ARN", "value": "ARN74512603070090" } },
        { "seq": 3, "stage": "CAPTURED", "actor": "PXE", "at": "2026-03-07T15:40:00.000Z", "status": "OK", "amountMinor": 940000 },
        { "seq": 4, "stage": "AUTH_RESIDUAL_RELEASED", "actor": "ISSUER", "at": "2026-03-14T09:00:00.000Z", "status": "OK", "amountMinor": 260000 },
        { "seq": 5, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-09T14:02:00.000Z", "status": "OK", "amountMinor": 940000, "reference": { "kind": "UTR", "value": "UTR2603090044" } }
      ],
      "explanation": {
        "rootCause": "PARTIAL_CAPTURE_DRIFT",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:2", "hop:3", "hop:4", "rule:AUTH_CAPTURE_DRIFT"],
        "merchant": "You authorized 12,000 and captured 9,400, so 9,400 was settled to you. The remaining 2,600 was never taken from the customer. Their bank released the hold on it on 14 March, which is why they may have seen 12,000 blocked for a week before it returned.",
        "support": "Partial capture. Merchant captured 9,400 of a 12,000 authorization, likely a line-item change after the order. Residual 2,600 held by the issuer until 14 March. If the customer complains about a missing 2,600, this is the answer and it has already resolved itself. No action.",
        "engineer": "NETWORK_AUTH at hop 2 for 1200000 minor, CAPTURED at hop 3 for 940000 minor after 6h40m. Delta 260000 released at hop 4 on the issuer's own 7-day timer. Payout matches capture, not auth, which is correct. Rule AUTH_CAPTURE_DRIFT matched on (capture < auth). Flagged rather than errored because partial capture is legitimate."
      },
      "demoNote": "Ledger reasoning and the amount-mismatch deviation. Good place to note the model never types any of these four numbers."
    },
    {
      "id": "PXE-009",
      "name": "duplicate-callback-suppressed",
      "title": "Bank sent the callback twice",
      "instrument": "NETBANKING",
      "rail": "NETBANKING",
      "amountMinor": 155000,
      "currency": "INR",
      "injectedCause": "DUPLICATE_CALLBACK",
      "expected": {
        "tag": "SUCCESS", "responseCode": "00", "deviations": ["DUPLICATE_SUPPRESSED"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "IDEMPOTENT_DUPLICATE"
      },
      "hops": [
        { "seq": 1, "stage": "REDIRECT_ISSUED", "actor": "PXE", "at": "2026-03-08T16:00:00.000Z", "status": "OK" },
        { "seq": 2, "stage": "BANK_SESSION", "actor": "PAYER_BANK", "at": "2026-03-08T16:00:31.000Z", "status": "OK" },
        { "seq": 3, "stage": "CALLBACK_RECEIVED", "actor": "PAYER_BANK", "at": "2026-03-08T16:01:12.000Z", "status": "OK", "code": "00", "reference": { "kind": "UTR", "value": "UTR2603080311" } },
        { "seq": 4, "stage": "CALLBACK_RECEIVED", "actor": "PAYER_BANK", "at": "2026-03-08T16:01:19.000Z", "status": "DUPLICATE_SUPPRESSED", "code": "00", "reference": { "kind": "UTR", "value": "UTR2603080311" }, "duplicateOf": 3 },
        { "seq": 5, "stage": "SETTLEMENT_SCHEDULED", "actor": "PXE", "at": "2026-03-08T16:01:20.000Z", "status": "OK", "batch": "SB-9944", "cycle": "T+1" },
        { "seq": 6, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-09T14:02:00.000Z", "status": "OK", "amountMinor": 155000 }
      ],
      "explanation": {
        "rootCause": "DUPLICATE_CALLBACK",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:3", "hop:4", "rule:IDEMPOTENT_DUPLICATE"],
        "merchant": "The customer's bank sent us the same confirmation twice, seven seconds apart. We recognised the second one and ignored it. You were credited once, for the correct amount. Nothing needs to be done.",
        "support": "Duplicate callback on the same UTR, suppressed by idempotency. Single ledger entry, single payout. If the customer says they were charged twice, they were not, and the single debit on their statement will confirm it.",
        "engineer": "CALLBACK_RECEIVED twice on UTR2603080311 at hops 3 and 4, 7s apart. Second marked DUPLICATE_SUPPRESSED with duplicateOf=3 by the idempotency guard before reaching the ledger. One LedgerEntry, one payout of 155000 minor. Rule IDEMPOTENT_DUPLICATE matched. This is a correctness guarantee firing, recorded rather than silent."
      },
      "demoNote": "A deviation representing the system working. Shows the deviation list is not a bug list."
    },
    {
      "id": "PXE-010",
      "name": "beneficiary-bank-timeout",
      "title": "Beneficiary bank unavailable",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 89000,
      "currency": "INR",
      "injectedCause": "BENEFICIARY_BANK_UNAVAILABLE",
      "expected": {
        "tag": "FAILED", "responseCode": "U28", "deviations": ["AUTO_REVERSAL_PENDING"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "BENEFICIARY_DOWN_AUTOREVERSE"
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T19:10:00.000Z", "status": "OK" },
        { "seq": 2, "stage": "QR_SCANNED", "actor": "CUSTOMER_PSP", "at": "2026-03-08T19:10:04.000Z", "status": "OK" },
        { "seq": 3, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T19:10:05.200Z", "status": "OK", "code": "00", "reference": { "kind": "RRN", "value": "445100001077" } },
        { "seq": 4, "stage": "PAYEE_CREDIT", "actor": "PAYEE_BANK", "at": "2026-03-08T19:10:35.000Z", "status": "FAILED", "code": "U28", "latencyMs": 29800 },
        { "seq": 5, "stage": "AUTO_REVERSAL_INITIATED", "actor": "NPCI", "at": "2026-03-08T19:10:36.000Z", "status": "PENDING", "slaHours": 48 }
      ],
      "explanation": {
        "rootCause": "BENEFICIARY_BANK_UNAVAILABLE",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:3", "hop:4", "hop:5", "rule:BENEFICIARY_DOWN_AUTOREVERSE"],
        "merchant": "The customer was debited but our receiving bank was not reachable, so the credit could not complete. The network has already started an automatic reversal and the customer will get their money back within 48 hours. You should not treat this order as paid.",
        "support": "U28 at PAYEE_CREDIT after a 29.8s timeout. The customer WAS debited, so they will call. Auto-reversal initiated at 19:10:36 with a 48h SLA. Give the customer the exact reversal window and do not ask them to raise a dispute, because the reversal is already running. Merchant should not ship.",
        "engineer": "PAYER_DEBIT succeeded at hop 3 under RRN 445100001077. PAYEE_CREDIT failed U28 at hop 4 after 29800ms. AUTO_REVERSAL_INITIATED at hop 5, PENDING, 48h SLA. Asymmetric state: debit without credit. Rule BENEFICIARY_DOWN_AUTOREVERSE matched. Watch for reversal confirmation; absence inside SLA escalates to PXE-014's class."
      },
      "demoNote": "The scenario where the customer is out of pocket. The support rendering is deliberately the most actionable of the three."
    },
    {
      "id": "PXE-011",
      "name": "system-malfunction-unmapped",
      "title": "System malfunction, no rule matches",
      "instrument": "UPI",
      "rail": "UPI_QR",
      "amountMinor": 445000,
      "currency": "INR",
      "injectedCause": "SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK",
      "expected": {
        "tag": "FAILED", "responseCode": "96",
        "deviations": ["UNEXPLAINED_TERMINAL", "LEDGER_ASYMMETRY"], "debt": 1,
        "explanationRequired": true, "path": "MODEL", "modelCalls": 1, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "INTENT_CREATED", "actor": "PXE", "at": "2026-03-08T20:00:00.000Z", "status": "OK" },
        { "seq": 2, "stage": "QR_SCANNED", "actor": "CUSTOMER_PSP", "at": "2026-03-08T20:00:05.000Z", "status": "OK" },
        { "seq": 3, "stage": "SWITCH_REQUEST", "actor": "NPCI", "at": "2026-03-08T20:00:05.400Z", "status": "PARTIAL_ACK", "latencyMs": 400 },
        { "seq": 4, "stage": "PAYER_DEBIT", "actor": "PAYER_BANK", "at": "2026-03-08T20:00:07.000Z", "status": "OK", "code": "00", "reference": { "kind": "RRN", "value": "445100001190" } },
        { "seq": 5, "stage": "SWITCH_RESPONSE", "actor": "NPCI", "at": "2026-03-08T20:00:21.000Z", "status": "FAILED", "code": "96", "latencyMs": 14000 },
        { "seq": 6, "stage": "PAYEE_CREDIT", "actor": "PAYEE_BANK", "at": null, "status": "NOT_RECEIVED" }
      ],
      "explanation": {
        "rootCause": "SWITCH_STATE_DESYNC_AFTER_PARTIAL_ACK",
        "determinable": true,
        "confidence": 0.78,
        "hypothesis": true,
        "citations": ["hop:3", "hop:4", "hop:5", "hop:6"],
        "merchant": "The customer was debited but the payment network reported a system error before the credit reached us, so this order is not paid. We have flagged it for manual reconciliation and the customer's bank will either complete or reverse it. We will confirm within 48 hours.",
        "support": "System malfunction, code 96, after a partial acknowledgement at the switch. Customer is debited and we hold no credit. This is NOT a standard auto-reversal path because the switch never reached a terminal agreement with the payer bank. Escalate to reconciliation with the RRN. Do not tell the customer a reversal is guaranteed.",
        "engineer": "Proposed cause, confidence 0.78, unconfirmed. SWITCH_REQUEST returned PARTIAL_ACK at hop 3, itself unusual. PAYER_DEBIT then succeeded at hop 4 under RRN 445100001190. SWITCH_RESPONSE returned 96 at hop 5 after 14s. PAYEE_CREDIT never arrived. The pattern is consistent with the switch losing state between the partial ack and the payer bank's success, leaving the debit orphaned. No rule matches (PARTIAL_ACK, then success, then 96). Candidate for a new rule once a second instance is observed."
      },
      "demoNote": "Beat 8. The first model call in the entire demo, at roughly minute seven. The token counter moves off zero here and that transition IS the funnel argument."
    },
    {
      "id": "PXE-012",
      "name": "recon-fee-applied-twice",
      "title": "Reconciliation break, ledger disagrees with the bank",
      "instrument": "CARD",
      "rail": "CARD_DOMESTIC",
      "amountMinor": 4120712,
      "currency": "INR",
      "injectedCause": "MDR_FEE_APPLIED_TWICE_IN_BATCH",
      "expected": {
        "tag": "SUCCESS", "responseCode": "00",
        "deviations": ["UNRECONCILED", "AMOUNT_MISMATCH"], "debt": 1,
        "explanationRequired": true, "path": "MODEL", "modelCalls": 1, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "NETWORK_AUTH", "actor": "NETWORK", "at": "2026-03-05T11:00:00.000Z", "status": "OK", "code": "00", "amountMinor": 4120712 },
        { "seq": 2, "stage": "CAPTURED", "actor": "PXE", "at": "2026-03-05T11:00:02.000Z", "status": "OK", "amountMinor": 4120712 },
        { "seq": 3, "stage": "SETTLEMENT_SCHEDULED", "actor": "PXE", "at": "2026-03-05T11:00:03.000Z", "status": "OK", "batch": "SB-9702", "cycle": "T+2" },
        { "seq": 4, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-07T14:02:00.000Z", "status": "OK", "amountMinor": 4120338 },
        { "seq": 5, "stage": "RECON_BREAK_DETECTED", "actor": "PXE", "at": "2026-03-07T18:30:00.000Z", "status": "OPEN", "expectedMinor": 4120712, "actualMinor": 4120338, "deltaMinor": 374 }
      ],
      "explanation": {
        "rootCause": "MDR_FEE_APPLIED_TWICE_IN_BATCH",
        "determinable": true,
        "confidence": 0.71,
        "hypothesis": true,
        "citations": ["hop:2", "hop:4", "hop:5"],
        "merchant": "We settled 3.74 less than the captured amount. The shortfall matches a second application of the processing fee for this batch, which appears to be an error on our side rather than yours. It is open for correction and you will be credited the difference.",
        "support": "Reconciliation break of 374 paise on batch SB-9702. Proposed cause is a duplicated MDR application, not a bank shortfall. Do not tell the merchant the bank underpaid until reconciliation confirms. Break is OPEN.",
        "engineer": "Proposed cause, confidence 0.71, unconfirmed. Captured 4120712 minor, credited 4120338 minor, delta 374 minor. The delta equals the batch MDR rate applied to this payment a second time, which is why the hypothesis is fee duplication rather than a bank-side shortfall. Cross-batch confirmation needed before this becomes a rule: check whether every payment in SB-9702 shows a proportional delta."
      },
      "demoNote": "The second MODEL-path scenario. The model reasons about an arithmetic relationship no rule encodes yet. Good answer to 'what is the model actually FOR'."
    },
    {
      "id": "PXE-013",
      "name": "issuer-soft-decline-then-success",
      "title": "Declined then succeeded on retry cascade",
      "instrument": "CARD",
      "rail": "CARD_DOMESTIC",
      "amountMinor": 320000,
      "currency": "INR",
      "injectedCause": "ISSUER_SOFT_DECLINE_RETRY_SUCCESS",
      "expected": {
        "tag": "SUCCESS", "responseCode": "00", "deviations": ["RETRY_CASCADE"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "SOFT_DECLINE_RECOVERED"
      },
      "hops": [
        { "seq": 1, "stage": "AUTH_REQUEST", "actor": "PXE", "at": "2026-03-08T08:00:00.000Z", "status": "OK" },
        { "seq": 2, "stage": "NETWORK_AUTH", "actor": "NETWORK", "at": "2026-03-08T08:00:01.100Z", "status": "DECLINED", "code": "91", "note": "issuer unavailable" },
        { "seq": 3, "stage": "NETWORK_AUTH", "actor": "NETWORK", "at": "2026-03-08T08:00:31.400Z", "status": "DECLINED", "code": "91", "retryOf": 2 },
        { "seq": 4, "stage": "NETWORK_AUTH", "actor": "NETWORK", "at": "2026-03-08T08:02:02.000Z", "status": "OK", "code": "00", "retryOf": 3, "reference": { "kind": "ARN", "value": "ARN74512603080810" } },
        { "seq": 5, "stage": "CAPTURED", "actor": "PXE", "at": "2026-03-08T08:02:03.000Z", "status": "OK", "amountMinor": 320000 },
        { "seq": 6, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-10T14:02:00.000Z", "status": "OK", "amountMinor": 320000 }
      ],
      "explanation": {
        "rootCause": "ISSUER_SOFT_DECLINE_RETRY_SUCCESS",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:2", "hop:3", "hop:4", "rule:SOFT_DECLINE_RECOVERED"],
        "merchant": "The customer's bank was briefly unavailable and declined the first two attempts. The third attempt, two minutes later, went through. The payment succeeded and has been settled to you in full.",
        "support": "Two soft declines with 91, issuer unavailable, recovered on the third attempt at 08:02:02. The customer may have seen two failure messages before the success. Reassure them there is only one charge and the statement will show one.",
        "engineer": "NETWORK_AUTH declined 91 at hops 2 and 3, succeeded at hop 4 after 122s total. Retry chain linked via retryOf. Single capture, single payout. Rule SOFT_DECLINE_RECOVERED matched on (terminal success preceded by soft-decline codes). Deviation recorded so retry cost is visible in aggregate; three auth attempts is three network fees."
      },
      "demoNote": "Explains a success the customer experienced as two failures. A very common real ticket, and a good demonstration of why SUCCESS still carries a debt."
    },
    {
      "id": "PXE-014",
      "name": "beneficiary-silence-undeterminable",
      "title": "Beneficiary bank sent nothing at all",
      "instrument": "UPI",
      "rail": "UPI_PAYOUT",
      "amountMinor": 2750000,
      "currency": "INR",
      "injectedCause": "UNDETERMINABLE_BY_DESIGN",
      "expected": {
        "tag": "PENDING", "responseCode": null,
        "deviations": ["ABSENT_TERMINAL_EVENT", "SLA_BREACHED"], "debt": 1,
        "explanationRequired": true, "path": "ABSTAIN", "modelCalls": 1, "rule": null
      },
      "hops": [
        { "seq": 1, "stage": "PAYOUT_INSTRUCTED", "actor": "PXE", "at": "2026-03-09T11:02:00.000Z", "status": "OK", "amountMinor": 2750000, "expectedBy": "2026-03-09T17:02:00.000Z" },
        { "seq": 2, "stage": "PAYOUT_ACKNOWLEDGED", "actor": "MERCHANT_BANK", "at": "2026-03-09T11:02:04.000Z", "status": "OK" },
        { "seq": 3, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": null, "status": "ABSENT", "elapsedHours": 71, "slaHours": 6 }
      ],
      "explanation": {
        "rootCause": null,
        "determinable": false,
        "confidence": null,
        "hypothesis": false,
        "abstained": true,
        "citations": ["hop:1", "hop:2", "hop:3"],
        "candidatesConsidered": [
          { "cause": "BANK_CUTOFF_MISSED", "evidence": "none" },
          { "cause": "BENEFICIARY_ACCOUNT_FROZEN", "evidence": "none" },
          { "cause": "INSTRUCTION_NEVER_TRANSMITTED", "evidence": "none" }
        ],
        "merchant": "We instructed your bank to credit 27,500 on 9 March and they acknowledged receiving the instruction. They have not confirmed the credit, and it is now 71 hours past the six-hour window. We cannot tell you why from what we can see, because your bank has not sent us anything further. We have escalated and will update you.",
        "support": "Payout acknowledged, never confirmed. 71h elapsed against a 6h SLA. We genuinely do not know the cause: there is no event to read. Do NOT offer the merchant a reason. Escalate to the banking team with the payout reference and tell the merchant exactly what we have asked and when we expect an answer.",
        "engineer": "PAYOUT_INSTRUCTED at hop 1, ACKNOWLEDGED at hop 2 after 4s. PAYOUT_CREDITED absent at 71h against a 6h SLA. No further event from MERCHANT_BANK of any kind. Three causes are consistent with this evidence and none is distinguishable from it: cutoff miss, account freeze, transmission failure. Correct output is abstention. Any confident attribution here is a false attribution."
      },
      "demoNote": "Beat 11, the single most revealing test in the suite. A model that invents a cause here FAILS."
    },
    {
      "id": "PXE-015",
      "name": "late-netbanking-callback",
      "title": "Callback arrived after the customer closed the tab",
      "instrument": "NETBANKING",
      "rail": "NETBANKING",
      "amountMinor": 98000,
      "currency": "INR",
      "injectedCause": "LATE_CALLBACK_AFTER_SESSION_CLOSE",
      "expected": {
        "tag": "SUCCESS", "responseCode": "00",
        "deviations": ["LATE_CALLBACK", "CUSTOMER_SAW_FAILURE"], "debt": 1,
        "explanationRequired": true, "path": "RULE", "modelCalls": 0, "rule": "LATE_CALLBACK_RECONCILED"
      },
      "hops": [
        { "seq": 1, "stage": "REDIRECT_ISSUED", "actor": "PXE", "at": "2026-03-08T14:00:00.000Z", "status": "OK" },
        { "seq": 2, "stage": "BANK_SESSION", "actor": "PAYER_BANK", "at": "2026-03-08T14:00:28.000Z", "status": "OK" },
        { "seq": 3, "stage": "SESSION_ABANDONED", "actor": "PXE", "at": "2026-03-08T14:15:00.000Z", "status": "TIMEOUT", "note": "no callback within 15m" },
        { "seq": 4, "stage": "CALLBACK_RECEIVED", "actor": "PAYER_BANK", "at": "2026-03-08T14:41:00.000Z", "status": "OK", "code": "00", "lateBy": "26m", "reference": { "kind": "UTR", "value": "UTR2603080455" } },
        { "seq": 5, "stage": "STATE_RECONCILED", "actor": "PXE", "at": "2026-03-08T14:41:02.000Z", "status": "OK", "from": "ABANDONED", "to": "SUCCESS" },
        { "seq": 6, "stage": "PAYOUT_CREDITED", "actor": "MERCHANT_BANK", "at": "2026-03-09T14:02:00.000Z", "status": "OK", "amountMinor": 98000 }
      ],
      "explanation": {
        "rootCause": "LATE_CALLBACK_AFTER_SESSION_CLOSE",
        "determinable": true,
        "confidence": 1.0,
        "citations": ["hop:3", "hop:4", "hop:5", "rule:LATE_CALLBACK_RECONCILED"],
        "merchant": "The customer's bank took 41 minutes to confirm this payment, long after we had timed the session out and shown a failure. The money did arrive and you have been settled. If the customer told you it failed, they were looking at our screen, not their bank's.",
        "support": "Classic late callback. We showed the customer a failure at 14:15, the bank confirmed success at 14:41, and we reconciled the state forward. The customer WILL believe this failed and may have paid again by another method. Check for a second payment from the same customer before responding.",
        "engineer": "SESSION_ABANDONED at hop 3 on our own 15m timer. CALLBACK_RECEIVED at hop 4, 26m past that timeout, code 00. STATE_RECONCILED at hop 5 moved ABANDONED to SUCCESS. Rule LATE_CALLBACK_RECONCILED matched on (terminal state reached after abandonment). The CUSTOMER_SAW_FAILURE deviation is derived, not observed, and exists to drive the duplicate-payment check."
      },
      "demoNote": "The support rendering carries an instruction the other two do not: check for a duplicate payment. Audience rendering is about action, not tone."
    }
  ]
}
```
