# PXE, context

The single context file for this repository. Architecture, routes, schema and decisions.
It is not a changelog and it is not a copy of the reference document.

**The normative document is [planner/payment-explainability-reference.md](planner/payment-explainability-reference.md).**
If this file and that document disagree, that document wins. If the code and that document
disagree, the code is wrong.

---

## Where things are

```
ExplainerPay/
├── docker-compose.yml          4 services, 4 healthchecks
├── .env.example                POSTGRES_*, GEMINI_API_KEY
├── context.md                  this file
├── planner/                    the reference document
├── api/                        pxe-api, Java 21, Spring Boot 3.3.5, ONE Maven module
├── ai/                         pxe-ai , Python 3.12, FastAPI
├── web/                        pxe-web, Next.js 15, React 19, TypeScript
└── data/                       payment-scenarios.json, ambiguity-set.json, expectations.json,
                                rail-sequences.json, causes.json, cause-templates.json,
                                merchants.json, payables.json
```

## Containers

| Service | Host port | Container port | Health | Holds DB credentials |
| --- | --- | --- | --- | --- |
| `postgres` | 15432 | 5432 | `pg_isready` |, |
| `pxe-api` | 18080 | 8080 | `GET /actuator/health` (includes the `db` component) | yes |
| `pxe-ai` | 18000 | 8000 | `GET /health` | **no, by construction** |
| `pxe-web` | 13000 | 3000 | `GET /` | no |

Published host ports are shifted off the defaults and overridable via `PXE_PORT_*`, because
unrelated projects commonly hold 5432/8080/8000/3000, one on this machine does. Container-internal
ports are standard, so nothing inside the network is affected.

Four containers is the complexity budget. There is no broker, no Temporal, no Redis and no
object storage; reference section 5 says why each is absent. `pxe-ai` is a separate process so
that *"the model has no database connection"* is verifiable by reading `docker-compose.yml`.

## Routes

| Route | Service | Phase |
| --- | --- | --- |
| `GET /actuator/health` | pxe-api | 0 |
| `GET /health` | pxe-ai | 0 |
| `GET /` | pxe-web | 0 (placeholder; becomes a redirect to `/pay`) |
| `GET /api/scenarios/report` | pxe-api | 1, what landed in the database, read back from it |
| `GET /api/deviations/report` | pxe-api | 2, the computed L2 set for every payment |
| `GET /api/payments/{id}/deviations` | pxe-api | 2 |
| `GET /api/eval` | pxe-api | 3, runs the golden set and reports the eight metrics |
| `/eval` | pxe-web | 3, the harness screen, demo beat 12 |
| `GET /api/payments/{id}/explanation` | pxe-api | 4, the L3 attribution, its citations and its rule |
| `GET /api/debt` | pxe-api | 4, the queue, sorted by exposure |
| `GET /api/payments` | pxe-api | 5, the scenario selector |
| `GET /api/payments/{id}` | pxe-api | 5, the whole payment at once |
| `GET /api/payments/{id}/stream` | pxe-api | 5, SSE, one frame per hop |
| `GET /api/counters` | pxe-api | 5, the two widgets |
| `/pay`, `/payment/[id]`, `/debt` | pxe-web | 5 |
| `GET /api/health` | pxe-web | 5, static, so the web container never depends on pxe-api to be healthy |
| `POST /api/payments/{id}/explain` | pxe-api | 6, the only route that can spend a token |
| `GET /health`, `/prompts` | pxe-ai | 6, the frozen prompt versions |
| `POST /jobs/hypothesis`, `/jobs/synthesis` | pxe-ai | 6, Job B and Job A |
| `GET /api/grounding/rules` | pxe-api | 7, G1 to G9 |
| `GET /api/grounding/hits/{id}` | pxe-api | 7, which rules fired on a payment |
| `POST /api/grounding/probe/{id}/{rule}` | pxe-api | 7, a malformed response through the real validator |
| `GET /api/grounding/rejections` | pxe-api | 7, the rejection log |
| `/grounding` | pxe-web | 7, beat 10 |
| `GET /api/merchants` | pxe-api | who can be paid, what each is owed and what each has unexplained |
| `GET /api/payables` | pxe-api | section 8.1, the payout queue, oldest due date first, `?merchant=` optional |
| `POST /api/payables/reset` | pxe-api | back to what the file says, leaving payments and debts alone |
| `POST /api/pay` | pxe-api | the only route that creates a payment, `?payable=` applies it to a bill |
| `POST /api/pay/arm`, `GET /api/pay/armed` | pxe-api | what the rails do on the next scan |
| `/checkout`, `/scan` | pxe-web | where the QR lands: merchant, then bill, then amount |

The four screens of reference section 18, `/pay`, `/payment/[id]`, `/debt`, `/eval`, arrive in
phases 3 and 5.

## Schema

### What is populated

Phase 1 loads **L0 only**: payment identity and the hop sequence, with references derived from the
hops. `payments.tag`, `response_code`, `terminal_at` and the three `debt_*` columns exist in the
table and are deliberately unmapped on the `Payment` entity: they are derived from hops by later
phases, not loaded. `expected`, `explanation`, `injectedCause` and `demoNote` are golden data and
are **never written to the database**, so nothing downstream of ingestion can reach ground truth by
querying. The eval harness reads them from the file.

Derived on load: `payment_references` rows come from hops carrying a `reference`, and
`superseded_by` links the next reference of the same kind **only when its value differs**, PXE-006
links its two RRNs, PXE-009 repeats one UTR and links nothing. Hop fields outside the typed columns
land in `attrs`, which is asserted by test, so a field added to the dataset is carried rather than
dropped.

Phase 2 adds **L2**: `deviations` rows, one per detected type. `expected`, `actual` and `severity`
stay null until the console of phase 5 is the first thing to render them. Detection is a pure
function and is recomputed from scratch on every startup, so the table is derived state, never a
record.

Phase 4 adds **L3**: `explanations` (path, root cause, citations, fact-set hash), `rule_hits`, and
the resolved outcome and debt columns on `payments`. `tag` and `response_code` are **derived from
the hops**, never loaded, `expected.tag` in the dataset is golden data the pipeline has to reach on
its own. The three audience texts stay null: L3 is a cause with citations, L4 is the wording, and
the renderers arrive with the phase that owns them.

Phase 3 adds no rows to any table. It reads: `explanations` and `model_calls` are mapped on their
**read** surface only, being the fields the harness evaluates. The write surface, `level`,
`prompt_version`, `generated_at` and the three audience texts, is mapped by the phase that first
produces an explanation.

Phase 5 adds no tables. It adds the console and the stream: `data/rail-sequences.json` supplies the
per-rail happy path that pre-reserves the layout, and the SSE endpoint replays a payment as
`skeleton` → `hop`* → `outcome` → `explanation`? → `done`.

Phase 6 adds the model path: `explanations` rows with path `MODEL` or `ABSTAIN` at L4, and
`model_calls` rows for every call **including the refused ones**: an admission decision that says
no is the interesting half of the funnel and cannot be inferred from a counter that only counts
spending.

Phase 7 adds the grounding contract: `V2` gives `model_calls` a `rejected_payload`, so a rejection
can be read rather than described. Phase 8 adds `data/cause-templates.json`, from which the
deterministic paths render all three audiences without a model.

### Migration

`V1__init.sql` is the complete section 8 data model: `payments`, `payment_hops`,
`payment_references`, `ledger_entries`, `deviations`, `explanations`, `rule_hits`, `model_calls`.
Two indexes carry meaning rather than performance:

- `payments_debt_queue_idx` on `(debt_open, amount_minor desc)`: the debt queue is a query, not
  a table and not a service.
- `explanations_fact_set_idx`, unique on `(payment_id, fact_set_hash)`, section 16's
  content-addressed cache expressed as a constraint, so a re-generation is a constraint
  violation rather than a convention nobody enforces.

`V3__merchant_payables.sql` adds section 8.1: twenty rows of ordinary accounts payable seeded from
`data/payables.json`. A check constraint keeps `remaining_minor` between zero and the amount raised,
because overpaying settles a bill here and does not turn into a balance this system does not hold.

**Payables are not explanation debt and the code must never conflate them.** `remaining_minor` comes
down only when the payment cleared (`SUCCESS` or `DEEMED_SUCCESS`) and only by the amount on the last
settlement hop that occurred, `PAYEE_CREDIT` on the UPI rails or `PAYOUT_CREDITED` on the card rails.
A settlement hop with no `occurred_at` credits nothing; one carrying no amount credits the payment in
full, because Appendix A states an amount there only when it differs. `com.pxe.payable.Payables`
reads the resolved outcome and the hops and writes one column. It never touches a deviation or an
explanation, so nothing owed can bend what the system says happened.

The crossing is the point. PXE-012 closes nothing and leaves the bill short by the doubled
processing fee while opening a debt; PXE-007 settles the bill late and opens a debt anyway; PXE-004
and PXE-014 credit nothing at all. Ten tests in `PayableTest` pin each of those.

## Decisions

**The reference document was amended before any code was written.** Five defects were found by
parsing Appendix A and checking it against sections 8, 9, 12 and 20. Each was fixed in the
document first:

- **A.** Settlement `timezone` was `Asia/Kolkata` with `creditByLocal: "18:00"`, but every
  `PAYOUT_CREDITED` in the dataset is `14:02:00Z` = 19:32 IST. Under the original semantics all
  six settle-bearing scenarios were `SETTLED_LATE`, including the two clean successes, and phase
  2's exit criterion was unreachable. The dataset's own `cutoffAt` is `18:00:00.000Z`, so 18:00
  was always meant as UTC. Now UTC, and exactly one scenario (PXE-007) is late, matching the
  declared deviation sets. Section 9 also now states that `T+N` counts from the
  `SETTLEMENT_SCHEDULED` hop and that there is no working-day calendar, PXE-007 settles on a
  Sunday and PXE-012 on a Saturday.
- **B.** Section 13 said Job A runs on *every explained payment*, which contradicts section 7's
  pipeline branch, section 17.4's precomputation, phase 4's "zero model calls" criterion and demo
  beats 4 and 6. Job A now runs only on the `MODEL` and `ABSTAIN` paths; `NONE`, `CODE` and `RULE`
  render from per-cause templates. `expected.modelCalls` is defined as Job B invocations.
- **C.** PXE-005 was labelled `path: "CODE"` with `responseCode: null` and a rule. It is a `RULE`
  scenario. Path counts are now NONE 2 / CODE 2 / RULE 8 / MODEL 2 / ABSTAIN 1.
- **D.** `payment_hops` could not hold the dataset: hops carry 28 distinct field names against 14
  columns, and four of the unmapped ones are read by section 12 rule predicates. `request_at,
  response_at` became a single nullable `occurred_at` (null = the hop did not happen);
  `cycle`, `cutoff_at`, `missed_cutoff`, `included` were promoted to columns; `attrs jsonb`
  carries the ten low-frequency fields.
- **E.** `references` is a reserved word in Postgres and `CREATE TABLE references` is a syntax
  error. Renamed `payment_references`, given an `id` for `superseded_by` to point at and a
  `hop_seq` for citations and `RECON_RRN_MUTATION` to join on.

Other decisions:

- **`payments.id` is the natural key**, `PXE-006` for a loaded scenario, generated for a live QR
  payment. Legible URLs and legible eval output.
- **`stage`, `status`, `actor`, `rail` are `text`, not enums.** 52 constants across four
  vocabularies; nothing before phase 4 switches on them.
- **The reference document lives in `planner/`.** It stays portable and self-contained.
- **`candidatesConsidered`** (PXE-014) goes inside `explanations.claims`, not its own column.
- **Gemini** is the model for Job A and Job B. `GEMINI_API_KEY` is read by `pxe-ai` alone.
- **`pxe-web` runs `next dev`** in the container for now. Phase 5 needs a production build to
  measure the section 17.7 budgets.
- **Published ports are shifted and overridable** (`PXE_PORT_POSTGRES`, `_API`, `_AI`, `_WEB`).
- **The ambiguity set is a second corpus** (`data/ambiguity-set.json`, reference section 22.1). Four
  cases built so a plausible answer is available and wrong, each a near-miss on a case the catalogue
  explains confidently. Scored for abstention correctness and false attribution; kept out of the
  coverage denominator, because folding them in would drag a true 80% coverage claim to 63%.
- **A cause is only named when the record rules the alternatives out.** Job B has to list its rivals
  and cite the hop that eliminates each one; an answer leaving a rival unresolved, or weighing no
  rival at all, is turned into an abstention before anyone sees it. Asking a model to be careful
  does not make it careful; refusing an answer whose own working does not support it does.
- **An abstention does not pay the debt** (reference section 3). A response code pays it, a rule
  pays it, a cited hypothesis pays it. "Cannot be determined" is the correct output and it is still
  nobody having explained the payment: the money is missing and a human has to go and get the
  answer. Closing it there would let the console trend to zero by giving up, which is the one way
  to make the number lie. PXE-014 stays on the queue, and the panel says so.
- **One shape for one concept.** `POST /explain` answered with a narrower record than the screen
  renders, one carrying no audience text, so an explanation arrived at the panel with the prose the
  pipeline had just written stripped out of it and the screen read "no rendering at this level"
  until the page was reloaded. It now answers with the same payment snapshot as
  `GET /api/payments/{id}`, so the tag, the deviations, the debt and the three renderings all move
  together. A test asserts the two return types stay identical.
- **The console calls the API cross-origin, so pxe-api sends CORS headers** (`PXE_CORS_ORIGINS`).
  Without them an EventSource fails on open and reports nothing useful: the page showed a finished
  stream with an empty timeline, which reads as a backend that returned no hops rather than a
  browser that was never allowed to ask. The `ask why` POST was blocked the same way.
- **A failed stream falls back to the record.** The page is already server-rendered with the whole
  payment; letting an unopened stream blank it out was the bug behind the empty timeline.
- **An abstention renders the absence.** G8 stops a narrative being written over dropped facts; it
  was never meant to leave the panel blank, and it did, on PXE-014, the single most revealing
  screen in the demo. `AbstentionRenderer` builds the three audiences from the record: what we
  asked for, what came back, what did not, how long it has been, and which causes the evidence
  refuses to separate. It never depends on Job A, because the one screen that must always speak is
  the one where the system is admitting it does not know.
- **A rejected Job A still renders**, from the claims that passed the contract. Losing the wording
  is correct; showing the reader an empty panel is not.
- **Internal symbols are not what a reader reads.** `web/src/lib/labels.ts` maps every closed
  vocabulary, causes, deviations, tags, rails, stages, actors, paths, rules, to English, with a
  fallback that de-shouts any symbol nobody has written a label for, so adding a cause never leaves
  a hole in the interface. The symbol stays: on the cause it sits under the English title, and
  everywhere else it is the `title` attribute. `MDR_FEE_APPLIED_TWICE_IN_BATCH` is the right thing
  to store, cite and test against, and the wrong thing to show a merchant.
- **Prose is prose.** The console set everything in monospace, which made an explanation look like a
  log line: the opposite of the point of the product. Mono is now reserved for what you would copy
  and paste; the audience renderings are set in the body face.
- **A digit inside a placeholder is not a literal number.** `{amount_1}` is a slot; checking the
  raw string would have rejected the exact convention the prompt teaches. G4 masks placeholders
  before looking for digits, on both sides of the boundary.
- **Runners live outside the beans they drive** (`PipelineRunner`). A bean calling its own
  `@Transactional` method does not go through the proxy, so the annotation did nothing: every
  repository call opened its own transaction and returned a fresh detached entity, the timeline
  held one `Payment` and the loop mutated another, and every template asking for the resolved
  response code silently rendered nothing.
- **A probe is not an admitted model call.** It feeds a canned payload straight to the validator,
  so recording it as admitted made a payment look like it had spent a token and dropped
  deterministic coverage to 73%.
- **The deterministic paths render from per-cause templates** (`data/cause-templates.json`, 11
  causes x 3 audiences) and obey the same rule the model does: the template writes a slot, never a
  number, and `Slots` fills it from the record. A number in the merchant text came out of the
  ledger on the CODE and RULE paths exactly as it does on the MODEL path.
- **The root cause is chosen from a closed taxonomy** (`data/causes.json`, 30 entries, G9 in
  reference section 15) rather than invented. Section 22 scores an explanation by whether it *names*
  the injected cause, and a name is only checkable against a fixed list; left free, a correct
  reading of the record scores zero for picking a synonym. The list is deliberately **not**
  exhaustive and never can be: an exhaustive taxonomy would turn Job B into a classifier and make a
  novel failure mode unrepresentable, which is the one case the MODEL path exists for.
- **The taxonomy was tested for being a crutch, and is not one.** Removing the correct cause from
  the list and re-running PXE-011 and PXE-012 makes both abstain rather than pick the nearest
  neighbour, with tempting entries like `CHARGEBACK_ADJUSTMENT_NETTED_IN_BATCH` and
  `SWITCH_RESPONSE_LOST_IN_TRANSIT` still on the list. `UNDETERMINED` is the escape hatch that
  keeps a closed list honest.
- **A rejected Job B produces nothing and leaves the debt open.** Found by the leave-one-out
  experiment, which made the model violate the claim contract and turned up an unhandled 500. There
  is no partial cause to keep, so the route answers 422 and the rejection is the record. A rejected
  Job A is different: it costs the wording and keeps the attribution.
- **The model path is on demand, never at startup.** `POST /api/payments/{id}/explain` is the only
  route that can spend a token. If the pipeline pre-called the model on boot, the counter would be
  non-zero before the demo began and the entire funnel argument would collapse.
- **`resolveAll()` no longer wipes `explanations`.** It skips any payment whose stored
  `fact_set_hash` still matches, which is section 16 enforced rather than described: a restart
  re-spends nothing.
- **A rejected Job A costs the wording, not the attribution.** Pydantic rejects a rendering that
  contains a digit; the pipeline records the rejection with `rejected_by` and keeps the cause and
  its citations. `AiClient.Rejected` (502) and `AiClient.Unavailable` (503) are distinct because a
  rejection means tokens were spent and a rule fired, while an outage means neither.
- **The test suite never calls a live model.** `ModelPathTest` stubs `AiClient`; `support/Baseline`
  resets every suite to the deterministic state first, since the tests share one Postgres. An
  assertion that costs money and can fail for weather reasons is not an assertion.
- **The skeleton is per rail, not per instrument** (`data/rail-sequences.json`, reference 17.3).
  It is the happy path, not the union: a row that never lights *is* the absent node, and an
  unplanned event (retry, reversal, batch closure, recon break) appends below.
- **Page order is header → outcome → explanation → timeline.** The timeline is the only block that
  grows and nothing sits under it, so an append moves nothing already on screen. Every hop row is
  34px in all three states, so a hop landing changes colour and never height. That is how CLS is
  zero by construction.
- **CLS is zero by construction, not by measurement.** No browser measures it; there is no
  Playwright. What is enforced is `npm run check:budgets`, which fails the build if a `@keyframes`
  or `transition` names width, height, top, left, margin or padding, the section 17.6 rule, as a
  check rather than a comment.
- **Hops are paced (`pxe.stream.hop-delay-ms`, 320ms), not replayed at recorded intervals.**
  PXE-007 spans nine days. The wait is still the content, compressed to demo tempo.
- **A `NONE`-path payment gets no `explanations` row.** Section 3 is explicit that a clean payment
  gets a tag, a timeline and a settlement date and nothing else, so a row in a table called
  `explanations` for a payment that owes none is the wrong shape. Deterministic coverage instead
  counts a payment as resolved when it spent no tokens and is either explained or terminal with no
  debt. Still 12/15.
- **The response code recorded on a payment is the code of the hop that decided its tag.** PXE-006
  reports U30 rather than the 00 of the retry that followed: the timeout is what made it a deemed
  success, and the later success does not erase how it got there.
- **Rules are tried in order, first match wins, and no two overlap on any scenario**, asserted by
  test, so the ordering is a tie-break that is never used rather than a hidden priority.
- **The fact-set hash covers L0 and L2 only.** No root cause, no path, no text: the hash identifies
  the question, not the answer, or the cache would miss every time the answer changed.
- **The harness exists before anything that explains.** It reads ground truth from
  `data/payment-scenarios.json` and never from Postgres, so nothing that produces an explanation can
  consult the answer while producing it.
- **A metric reports its denominator beside its value.** Five metrics are `0/0` at phase 3 and read
  `not measured`; three have real denominators (12, 1, 15) and read `not met`. Both display as 0%,
  and they mean different things.
- **Groundedness and numeric fidelity are not approximated before their enforcer exists.** They are
  supplied by the grounding validator in phase 7 and report `not measured` until then, rather than
  being faked with a claim-has-a-citation proxy that would over-report from phase 4 onward.
- **The deviation catalogue is the only producer of deviation rows** (reference section 9.1).
  Section 9's four conditions describe shapes, not detectors: a literal reading fires on PXE-003,
  where a decline correctly has no next stage, and on PXE-009, which records no `STATE_RECONCILED`.
  Neither deviates.
- **Detection reads elapsed time from the record, never from a wall clock.** There is no clock, and
  PXE-014's `elapsedHours: 71` is not derivable from any consistent "now". A live payment will need
  a configured clock; a replayed one must not use it, or the golden set stops being reproducible.
- **`ABSENT` and `NOT_RECEIVED` are distinct on purpose**, which corrects an earlier reading of them
  as duplicates. `ABSENT` is an expected event that has not arrived and whose absence is the
  finding (PXE-014). `NOT_RECEIVED` is an event that will never arrive because the flow already
  failed upstream (PXE-011). Only `ABSENT` raises `ABSENT_TERMINAL_EVENT`.
- **Section 11's code table is code, not data**, for now. Only the FULL/PARTIAL/NONE sort is used at
  L2; phase 4 may promote it to `data/` when the L3a-i path needs the descriptions too.
- **`data/` is bind-mounted read-only into pxe-api** at `/app/data`, so the dataset has one copy,
  at the repository root where section 6 puts it, and editing it needs no rebuild.
- **Tests are integration tests against the compose Postgres**, not Testcontainers. Docker Desktop
  on this machine exposes no engine endpoint the JVM can reach, and a second Postgres would add a
  dependency to prove what the first one already proves. Run `docker compose up -d` first, then
  `mvn test` from `api/`.
- **Merchants are real** (`data/merchants.json`, five of them, mapped to scenarios beside the
  dataset rather than inside it so Appendix A stays a record of what happened rather than of who was
  selling). `payments.merchant_id` was one hardcoded value, which made G2 vacuous and the debt queue
  an undifferentiated list. The checkout now asks who you are paying, and the queue answers the
  question ops asks first: whose money is unexplained.
- **Exposure is not a bill.** The figure under each merchant is money whose fate the system cannot
  account for. Nobody settles an explanation debt by paying it, and the checkout says so rather than
  implying a customer can.
- **A citation inside prose is not a literal number.** `hop:1` tripped G4 and threw away a valid
  hypothesis, the same class of bug as `{amount_1}`. Both sides now mask citations as well as
  placeholders before looking for digits.
- **The customer chooses the amount; the merchant arms the failure.** The QR is constant and opens
  a checkout; `ArmedScenario` holds what the rails will do next, set from the console. Nobody paying
  a QR decides whether their bank times out, and splitting the two is what makes the scenario
  selector honest rather than a magic wand.
- **Recorded figures scale with the amount paid.** A partial capture is a fraction of an
  authorization and a processing fee is a percentage of a capture, so scaling is the domain
  behaviour, not a trick to make the numbers agree. A scaled quantity that would round to zero is
  held at one minor unit: a shortfall of zero is a different payment from a shortfall too small
  to print.
- **A scan takes a payment in.** `POST /api/pay?as={scenario}` creates a payment with a fresh id
  under the same ingestion path the loader uses, runs the funnel, and `/scan` redirects to it. The
  event set is copied from a scenario because that is how a known failure is injected, and the
  timestamps are copied unchanged: rebasing them onto now would look more alive and would quietly
  break the expectation model, since a settlement deadline is an absolute time of day and the same
  event set would or would not be late depending on the hour you scanned.
- **The QR is real and scannable**, encoding a link to the selected payment on whatever host the
  console was opened from. The browser-side API base is derived from `window.location.hostname`
  rather than hard-coded, because on a phone `localhost` is the phone; CORS allows private ranges
  for the same reason. Verified end to end from the machine's LAN address: page, preflight and SSE.
- **QR wiring was deferred to phase 5** (PSP test mode vs simulated, reference section 23). The hop
  model stays rail-agnostic until then.

## Open against the document

Found during the read, not yet fixed, each deliberately left to the phase that needs it. Five
earlier items are gone: the deviation catalogue and expectation-row matching became sections 9.1
and 9.2, the clock is now a stated rule rather than a gap, `ABSENT` versus `NOT_RECEIVED` turned out
to be a real distinction rather than a duplication, and `payments.merchant_id` is now supplied by
`data/merchants.json`, which is what made G2 a check rather than a formality.

- Section 14 cites a reference by *value*; G1 says citations resolve to an *id*.
- The golden `merchant` / `support` / `engineer` texts are post-substitution renderings, not model
  output. Section 15 does not say so, and a harness built on the wrong reading fails G4 against
  its own golden set.
- Demo beat 10 has the model typing `18,400`, which is PXE-006: a `RULE` scenario that never
  reaches a model.
- The demo in section 23 is ten minutes (2+4+4) and the submission is five.
- **The model is not reproducible across fresh runs.** Temperature is zero and the response is
  schema-constrained, and it still varies: across three clean runs PXE-011 named the injected cause
  3/3, PXE-012 2/3 (abstaining on the third), PXE-014 abstained 2/3 with one response rejected
  outright. Determinism holds *after* the first answer, because section 16's cache never
  regenerates one. Nothing in the golden set fails because of this, and no run produced a false
  attribution, but cause accuracy moves between 91.7% and 100% depending on which answer got cached.
- **The taxonomy has no growth path yet.** A payment whose cause is genuinely absent from
  `causes.json` abstains, which is correct but loses information: "several causes fit and the record
  cannot separate them" (PXE-014) and "the record points clearly at something not on the list" are
  different findings, and only the second is a candidate for a new taxonomy entry and eventually a
  new rule. Splitting them needs a golden scenario first, per non-negotiable rule 8.
## Phases

Reference section 20, phases 0 to 8. Build stops at the end of each phase and reports its exit
criterion before the next begins.

| Phase | State |
| --- | --- |
| **0. Skeleton** | done, four healthy containers in 11.5s, V1 applied, 8 tables |
| **1. Model and loader** | done, 15 payments, 79 hops, 16 references; 5 tests green |
| **2. Timeline and expectations** | done, 16 detectors, computed set equals declared on 15/15 |
| **3. Eval harness** | done, eight metrics, all zero, `/eval` renders |
| **4. Code and rule paths** | done, coverage 12/15, 2 by code, 8 by rule, 0 tokens |
| **5. Console and stream** | done, SSE, four screens, skeleton reserved, budget check green |
| **6. AI service** | done, PXE-011 and PXE-012 produce hypotheses, PXE-014 abstains |
| **7. Grounding validator** | done, G1 to G9, substitution, rejection log, beat 10 |
| **8. Abstention and renderings** | done, PXE-014 abstains, three audiences, absent node |
