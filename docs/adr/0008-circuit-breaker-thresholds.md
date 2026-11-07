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

## Status of the pieces

| Piece | State |
|---|---|
| Breaker around each call, thresholds as above | Built, tested (unit, IT, real stack) |
| Fail fast with `503` + `Retry-After`, nothing created | Built, tested |
| `/actuator/circuitbreakers` and `/actuator/circuitbreakerevents` | Exposed |
| Simulator `INTERMITTENT` with a settable rate | Built (`?mode=INTERMITTENT&failureRate=0.05`) |
| Slow-call threshold | Not set, deliberately. It needs measured percentiles |
| Thresholds revisited under real load | Phase 8 |
