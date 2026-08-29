# Payment Explainability Engine

A payment traverses a real multi-stage backend: intent, switch, payer bank, payee bank, settlement,
payout, reconciliation. Every hop is recorded. When it succeeds cleanly the system says so and
nothing more. When it fails, stalls, or succeeds in a way that does not reconcile, the system **owes
an explanation** and pays that debt, grounded in the actual events, in language matched to whoever
is asking.

The specification is [planner/payment-explainability-reference.md](planner/payment-explainability-reference.md).
It is normative: if the code and the document disagree, the code is wrong.
[context.md](context.md) records what exists and why.

## Boot it

Requires Docker Desktop. Nothing else, no JDK, no Node, no Python on the host.

```bash
cp .env.example .env          # then put a Gemini key in GEMINI_API_KEY
docker compose up -d --wait
```

Roughly 15 seconds to four healthy containers on built images; the first build takes a few minutes
while Maven, npm and pip populate their caches.

| | |
| --- | --- |
| Console | http://localhost:13000 |
| API | http://localhost:18080 |
| AI service | http://localhost:18000 |
| Postgres | `localhost:15432`, database `pxe`, user `pxe`, password `pxe` |

Ports are shifted off 5432/8080/8000/3000 because unrelated projects commonly hold those. Override
with `PXE_PORT_WEB` and friends in `.env`.

```bash
docker compose logs -f pxe-api   # watch the pipeline resolve on startup
docker compose down              # stop
docker compose down -v           # stop and forget everything, for a clean demo take
```

## The four screens

| | |
| --- | --- |
| `/pay` | The QR and the two widgets. The scenario selector chooses what the next scan does. |
| `/payment/{id}` | The hop timeline, the outcome tag, the explanation, and the audience switcher. |
| `/debt` | The queue: open debts sorted by exposure. The number that should trend to zero. |
| `/eval` | The golden set, scored. Cause accuracy, false attribution, coverage, cost. |
| `/grounding` | The nine rules, and a malformed response caught by them. |

## What to look at first

**`/pay`, scan PXE-001.** Hops arrive one at a time. Tag `SUCCESS`, no deviation, no explanation.
`DEBT 0`, `TOKENS 0`. A payment that worked owes you nothing and cost nothing to say so.

**Scan PXE-006.** A UPI payment that timed out, retried under a new reference, and was skipped by
its own settlement batch. Fully explained, three audiences, every number substituted from the
ledger, with `TOKENS` still at zero, because a rule did it.

**Open PXE-011 and press "ask why".** No response code and no rule account for it, so admission
control admits it and the model is called. `TOKENS` moves off zero for the first time. The answer
comes back labelled `HYPOTHESIS`, with a confidence and its citations.

**`/grounding`, press "violate G4".** A response containing a literal `18,400` goes through the
real validator and is discarded whole, with the payload on screen. The model is structurally
prevented from typing a number; this is what happens when it tries.

**`/eval`.** Every metric, with its numerator and denominator, because a zero that means "nothing
measured yet" and a zero that means "failing" are different facts.

## Layout

```
docker-compose.yml     four containers, four healthchecks
api/                   pxe-api , Java 21, Spring Boot, one Maven module
ai/                    pxe-ai  , Python 3.12, FastAPI. Holds no database credentials.
web/                   pxe-web , Next.js 15, React 19
data/                  the dataset, the expectation model, the rail sequences,
                       the cause taxonomy and the per-cause templates
planner/               the reference document
```

`pxe-ai` is a separate process for one reason: *"the model has no database connection"* is a real
security property, and only a process boundary proves it. Read `docker-compose.yml` to check.

## Tests

```bash
docker compose up -d           # the suite runs against this Postgres
cd api && mvn test             # 55 tests
```

They are integration tests against the running stack, and they **reset the database** to the
deterministic baseline: twelve payments resolved, three debts open, zero tokens spent. That is the
state to start a demo recording from. It also means running the suite discards any model answers
you had cached.

The suite never calls a live model, `ModelPathTest` stubs the client. An assertion that costs money
and can fail for weather reasons is not an assertion.

```bash
cd web && npm run check:budgets   # section 17.7: compositor-only animations
```

## Wiring a real QR

Deferred, and the reason is worth knowing: a PSP test mode can force a decline, which reaches only
the response-code path. The interesting scenarios, a reference mutating across a retry, a fee
applied twice in a batch, are settlement and reconciliation failures that surface hours or days
later, and no sandbox emits them on demand. The scenario selector on `/pay` chooses which failure
the rails return. Say that out loud; pretending otherwise is not credible.
