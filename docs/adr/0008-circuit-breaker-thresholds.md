# ADR-008: Circuit breaker thresholds chosen against a 5% background error rate

- **Status:** Accepted
- **Date:** 2026-11-05
- **Scope:** `payment-service`, every call to the payment processor
- **Related:** [ADR-004](0004-idempotency-keys.md) (why a fail-fast `503` must create nothing),
  [ADR-006](0006-payment-state-machine.md) (why an invented outcome is never a fallback)

## Context

When the processor is down, every authorisation still costs a call, a thread and up to 2 seconds. A
circuit breaker stops making calls that are almost certain to fail. That protects our capacity and
gives the processor room to recover instead of a retry storm.

The danger is in the thresholds. Set them too sensitive and the breaker **flaps**: it opens on ordinary
background errors and refuses good payments while the processor is fine. Set them too lax and it opens
too late to help. Values copied from a blog post or left at library defaults are fitted to someone
else's traffic and someone else's error rate. These are fitted to ours, and the reasoning is written
down so the next person can redo it.

**The assumptions.** A realistic background error rate is about **5%**: occasional 5xx responses, a
dropped connection now and then. A real outage fails close to **100%** of calls. Traffic is low and
uneven. This is a portfolio service, and its traffic looks like a quiet merchant's.

## Decision

```yaml
slidingWindowType: COUNT_BASED
slidingWindowSize: 20
minimumNumberOfCalls: 10
failureRateThreshold: 50
waitDurationInOpenState: 10s
permittedNumberOfCallsInHalfOpenState: 3
automaticTransitionFromOpenToHalfOpenEnabled: true
registerHealthIndicator: false
```

**How each was chosen.** First, the analysis. A simulation fed each candidate one million calls at a
fixed failure rate and counted how often it tripped:

| Window / min calls / threshold | Trips per 1M calls at 5% | at 10% | at 20% | Calls before a dead processor trips it |
|---|---|---|---|---|
| **20 / 10 / 50%** (chosen) | **0** | 2 | 662 | **10** |
| 20 / 10 / 25% | 426 | 5,754 | 36,843 | 10 |
| 10 / 5 / 20% (typical blog values) | 15,928 | 48,432 | 117,610 | 5 |
| 100 / 100 / 50% (Resilience4j defaults) | 0 | 0 | 0 | 100 |

Exact tails make the same point: with 5% errors, P(≥10 of 20 calls fail) = 1.1 × 10⁻⁸, while
P(≥1 of 10 fail) = 0.40. A threshold that one failed call in ten can reach will trip on background
noise most of the time.

- **`failureRateThreshold: 50`.** Noise sits around 5% and outages close to 100%; 50% is far from both.
  The lower the threshold, the closer it gets to the noise. 25% already trips 426 times per million.
- **`slidingWindowSize: 20`, count-based.** A time-based window would be the better choice at steady,
  high volume. At low, uneven volume a 60-second window might hold 3 calls one minute and none the
  next, and a rate over 3 calls is mostly noise. Twenty calls is enough to estimate a rate and small
  enough to react within a few payments.
- **`minimumNumberOfCalls: 10`.** No judgement on fewer than 10 calls, so two failures after a restart
  can't open it. Resilience4j's default of 100 is the opposite mistake: at this traffic a dead
  processor would need 100 failed calls (about 50 failed payments) before anything happened.
- **`waitDurationInOpenState: 10s`.** Long enough that we're not probing a struggling processor several
  times a second, and short enough that recovery is noticed quickly. It's also the `Retry-After` the
  client is given, so it's an honest upper bound. It's a judgement call, not a measured one, and the
  real processor's recovery times would be the evidence to change it.
- **`permittedNumberOfCallsInHalfOpenState: 3`.** Three probes, and the 50% threshold applies to them:
  0 or 1 failure closes it, 2 or 3 reopen it. One probe would be a coin toss.
- **`automaticTransitionFromOpenToHalfOpenEnabled: true`. This one is not optional here.** Without it,
  Resilience4j only leaves `OPEN` when a call arrives. Our fail-fast check refuses requests before any
  call is made, so the breaker would stay open forever.
- **`registerHealthIndicator: false`.** With it on, an open breaker turns `/actuator/health` `DOWN`. An
  orchestrator then takes every instance out of service because one *dependency* is unhealthy, which
  turns a processor outage into a total outage, and replays and reads stop working too.

**What counts as a failure.** Recorded: `ConnectFailed`, `TimedOut`, `ConnectionBroken`, `OutcomeUnknown`
(5xx and unreadable responses). Ignored: `NotProcessed` (any 4xx, including 429), which says nothing
either way about the processor's health. Declines are return values, so they count as **successes**. A
run of declined cards is the processor working, and it can never open the breaker.

**Where it sits.** Inside the retry, around each call, so every attempt is one call the breaker sees.

**The fallback is a fast error, never an invented outcome.** When the breaker is open,
`POST /payments` returns `503` with `Retry-After` *before anything is written*. Nothing is created and
nothing charged, and a retry with the same `Idempotency-Key` works once the processor is back. A
fallback that answered "approved" would ship goods for a payment nobody authorised. One that answered
"declined" would lose a sale and tell the customer their card was refused when it wasn't.

### Alternatives rejected

- **Resilience4j defaults (100 / 100 / 50%).** It never flaps, but it takes 100 calls to react, which
  at our traffic is far too late.
- **Typical blog values (a small window, a 10–20% threshold).** They trip constantly on 5% noise. See
  the table.
- **A time-based window.** It's right for steady high volume, and it gives noisy rates at low volume.
  It's worth revisiting under real load (Phase 8).
- **Slow-call thresholds (`slowCallRateThreshold`).** Slow calls here already become `TimedOut` at the
  2s read timeout and are recorded. A call slower than normal but under 2s isn't counted, and that's
  a known gap. Adding a slow-call threshold would need measured latency percentiles to set it, and we
  don't have them yet.
- **Counting 429 as a failure.** A 429 is the processor protecting itself, which is arguably exactly when
  to back off. It's ignored for now because the rate limit, and whether to honour `Retry-After`, is its
  own decision. Revisit together with 429 retries.
- **Creating the payment and marking it `FAILED` when the breaker is open.** Its key would replay
  `FAILED` forever, so the client would need a new key to try again after a brief outage.

## Consequences

**What this gives us**

| Claim | Evidence |
|---|---|
| Full lifecycle, demonstrable | Real stack, 2026-11-05: 10 payments under `HTTP_500` → `CLOSED_TO_OPEN`; `503` in 13 ms with `Retry-After: 10`; `OPEN_TO_HALF_OPEN` after 10.0s with no traffic; 3 probes → `HALF_OPEN_TO_CLOSED`. `CircuitBreakerIT.theFullLifecycle` asserts the same three transitions in order. |
| No flapping at 5% | Real stack: 200 payments at `INTERMITTENT` 5% gave 9 failures, **no `503`s**, and the breaker ended `CLOSED`. `CircuitBreakerIT` runs the production config through 100,000 calls at 5% and asserts **zero** trips. The blog-style config, run through the same test, trips more than 500 times, so the test can tell them apart. |
| Opens fast on a real outage | 10 calls, asserted against the production config. |
| An open breaker writes nothing | `CircuitBreakerIT`: no payment row and no processor call while open. |
| Replays work while it's open | `CircuitBreakerIT.aReplayStillWorksWhileTheBreakerIsOpen`. |

**What this makes hard**

- **The thresholds encode an assumption about traffic.** At ten times the volume, a 20-call window
  covers a fraction of a second, and a time-based window becomes the better choice. These numbers need
  redoing with real traffic, which is Phase 8's job.
- **`HALF_OPEN` decides only once all three probes are in.** Two failed probes don't reopen it straight
  away: Resilience4j waits for the third. The test was first written expecting otherwise, and it
  failed. A fourth request that arrives during `HALF_OPEN` is refused at the call (`CIRCUIT_OPEN`),
  after its payment has been created, so it ends `FAILED`. The fail-fast check only sees `OPEN`.
- **The same race exists at the moment it opens.** A request that passed the check just before the
  breaker opened is refused at the call. It ends `FAILED` with `CIRCUIT_OPEN` recorded, and no money
  moves, but its key then replays `FAILED`.
- **One breaker for the whole processor.** It doesn't distinguish card networks or merchants. A real
  processor partially failing (say, one card scheme) would open it for everyone.
- **The event log forgets.** `/actuator/circuitbreakerevents` keeps the last 100 events, so after a busy
  period the transitions aren't there any more. The metrics endpoint (`resilience4j_circuitbreaker_*`
  in Prometheus) is the durable record.

## Bulkhead (added 2026-11-13)

**Why it's here as well as a timeout.** A timeout bounds *one* call. A bulkhead bounds *how many*
can be in flight at once. A processor that answers in 1.5s triggers no timeout, no error and no
breaker, yet every payment holds a request thread for those 1.5s. With 200 Tomcat threads and enough
incoming payments, all 200 end up waiting on the processor, and reads that never touch it queue
behind them.

**Decision.** `maxConcurrentCalls: 20`, `maxWaitDuration: 0`, around the whole create-and-authorise
path, taken before anything is written. A request that finds it full gets `503` with
`Retry-After: 1`, and nothing is created, the same contract as an open breaker. Replays need no
processor and no permit, so they are never refused. 20 is a tenth of Tomcat's threads: capacity is
about 160 payments/s at the normal 120ms and about 13/s at 1.5s. Not waiting is deliberate, because
a request waiting for a permit still holds its thread.

**Evidence (real stack, processor `SLOW` at 1.5s, 300 clients flooding `POST /payments` for 15s,
`GET /payments/{id}` timed every 100ms):**

| Run | Successful payments | Read p50 / p95 |
|---|---|---|
| No flood (baseline) | n/a | 24 / 35 ms |
| Bulkhead 20, clients honour `Retry-After` | 200 | **16 / 61 ms** |
| Bulkhead 20, clients retry instantly | 200 | 274 / 382 ms |
| No bulkhead | 2,067 | **836 / 1,119 ms** |
| No bulkhead, virtual threads | 2,866 | 11 / 51 ms |

- **200 successes** is the bulkhead doing exactly its arithmetic: 20 permits × 15s / 1.5s.
- **Clients retrying instantly** sent about 1,000 requests a second at the service, all refused. Reads
  slowed because the refusals themselves cost CPU. That's a client misbehaving, not the bulkhead
  failing, and it's why the `503` carries `Retry-After`.
- **Virtual threads** fix thread exhaustion on their own: reads stayed fast with no bulkhead. They do
  not bound anything else. All 300 calls went to the processor at once, more than ten times what the
  bulkhead allows. Against a real processor, that is the load that turns slow into down, and the
  breaker would then see it. Virtual threads make waiting cheap; they don't make the dependency, the
  database pool or the processor's capacity any bigger. The bulkhead stays.

`BulkheadIT` proves the cap and the refusal with MockMvc: the processor never sees more than 20 at
once, and refusals take well under a permitted call's time. MockMvc has no Tomcat thread pool, so the
thread-exhaustion half is only shown by the measurement above.

## One attempt row per processor request id (added 2026-11-13)

**Why.** A retry sends the same request id, and the processor treats it as the same authorisation:
at most one charge, however many calls it took. A row per call recorded one authorisation as several,
and a duplicated answer, the processor delivering one result twice, as two attempts.

**Decision.** `payment_attempts` has one row per (payment, operation, `processor_request_id`),
enforced by a unique constraint (V3 migration). A retry counts the call (`call_count`) on that row,
committed before the call, so a crash mid-retry still leaves it `PENDING`. This reverses Day 15's
"one row per call".

**What it gives up.** The outcome of each failed call before the last. The row keeps the final
outcome, the last call's latency and the call count. The earlier calls' outcomes are in the logs
only. Rows written before V3 were one per call; they carry `pre-v3:<id>` as their request id rather
than a guess.

**Evidence.** The simulator's `DUPLICATE_RESPONSE` authorises, sends half the answer and closes the
connection; a retry with the same id gets the same answer whole. On the real stack, three payments
each produced one attempt row with `call_count` 2: two `AUTHORIZED` with one authorisation id each,
and one `FAILED` from the simulator's 5% decline rate, whose decline was also delivered twice and
recorded once. `PaymentControllerIT` covers it through HTTP, and also shows the database refusing a
second row for the same request id.

**Found on the real stack, not by the tests.** The first `DUPLICATE_RESPONSE` threw an exception after
the half body, expecting Tomcat to drop the connection. It didn't: Spring Boot's error handling
appended its own JSON, and the client got a complete, garbled body, which it correctly called
`MALFORMED_RESPONSE` and didn't retry. The tests passed because the in-process fake processor closes
its connections differently. The simulator now declares the full length, sends half and asks Tomcat
to close the connection. The client also now classifies a body cut off mid-read as a broken
connection (retryable), separately from a body that arrived whole and made no sense (not retryable).

## Status of the pieces

| Piece | State |
|---|---|
| Breaker around each call, thresholds as above | Built, tested (unit, IT, real stack) |
| Fail fast with `503` + `Retry-After`, nothing created | Built, tested |
| `/actuator/circuitbreakers` and `/actuator/circuitbreakerevents` | Exposed |
| Simulator `INTERMITTENT` with a settable rate | Built (`?mode=INTERMITTENT&failureRate=0.05`) |
| Slow-call threshold | Not set, deliberately. It needs measured percentiles |
| Thresholds revisited under real load | Phase 8 |
| Bulkhead (20, no wait), `503` with nothing created | Built, tested (unit, `BulkheadIT`), measured on the real stack |
| One attempt row per processor request id | Built (V3), tested, shown on the real stack with `DUPLICATE_RESPONSE` |
| Simulator `OUTAGE` | Built: `503` for 30s, then back to `NORMAL` by itself. Drove the breaker's full lifecycle on the real stack with no second curl |
| The whole of ADR-008: all five Resilience4j patterns in one place | Day 18 |
