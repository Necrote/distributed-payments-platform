# ADR-008: Processor resilience - four Resilience4j patterns, one skipped, and in what order

- **Status:** Accepted
- **Date:** 2026-11-05 · **Rewritten:** 2026-11-15 (the whole of Phase 3 in one place; it began as
  the circuit-breaker thresholds alone - see [Revision notes](#revision-notes))
- **Scope:** `payment-service`, every call to the payment processor
- **Related:** [ADR-004](0004-idempotency-keys.md) (why a fail-fast `503` must create nothing, and why
  a replay never calls the processor), [ADR-006](0006-payment-state-machine.md) (why `PROCESSING`
  means "unknown", and why an invented outcome is never a fallback)

## Context

The processor is the only dependency on the payment path that this service doesn't control. It can
fail in five ways that matter, and each needs a different answer:

| The processor... | Danger to us | Danger to the money |
|---|---|---|
| answers slowly | Every waiting payment holds a request thread; enough of them and reads queue too | None, if we wait |
| stops answering mid-call | A thread waits as long as we let it | The charge may exist and we don't know |
| fails fast (refused connection, 5xx) | Retries multiply load on something already struggling | A blind retry of an unknown outcome can charge twice |
| keeps failing | Every request still pays a call and a wait to learn the obvious | None, but nothing gets through |
| answers twice | - | One authorisation recorded as two |

Two constraints come from the money, and they override convenience everywhere below:

1. **After a timeout we genuinely don't know whether the card was charged.** So a timeout is never a
   decline, and a retry is only safe if the processor can recognise it as the same request.
2. **No fallback may invent a business outcome.** "Assume approved" ships goods nobody paid for;
   "assume declined" tells a customer their card was refused when it wasn't. The only honest
   fallback is a clear error.

## Decision

### The five patterns

Resilience4j offers five core patterns. This service uses four.

| Pattern | Used? | Where | What it bounds |
|---|---|---|---|
| **Time limiter** | Yes, plus explicit connect/read timeouts | `ProcessorClient`, around each HTTP call | How long *one* call can take |
| **Retry** | Yes, driven by hand with a total deadline | `PaymentAuthorizer` | How many calls one payment makes, and for how long in total |
| **Circuit breaker** | Yes | `PaymentAuthorizer`, around each call, inside the retry | Whether to call at all while the processor is failing |
| **Bulkhead** (semaphore) | Yes | `IdempotentPaymentCreator`, around create-and-authorise | How many payments can be in flight at once |
| **Rate limiter** | **No** | - | - |

The skipped one, and the near-misses, are explained under [What was deliberately not
used](#what-was-deliberately-not-used). They say as much about the design as the four that are used.

### The order they wrap each other in

```
POST /payments
└─ bulkhead: at most 20 in flight; full -> 503 before anything is written
   └─ create the payment (its own transaction)
   └─ retry: up to 3 calls, same request id, only while another call fits in 4.4s
      └─ circuit breaker: open -> refused locally, nothing sent
         └─ time limiter: 2.5s for the whole call, on a virtual thread
            └─ HTTP: connect 500ms, read 2s
```

**The bulkhead is outermost, which is the reverse of Resilience4j's default.** Its annotation
order puts the bulkhead innermost, around the bare call: `Retry(CircuitBreaker(RateLimiter(
TimeLimiter(Bulkhead(call)))))`. Innermost, a permit is held only during the call itself and is
released during retry backoff - but the request thread is *still waiting* through that backoff. The
bulkhead exists to bound threads, so it must hold the permit for as long as the thread is held, retries
included. That's why it sits around the whole path, and why it lives in the creator, not the client.

**None of it is annotations.** `@Retry` and `@CircuitBreaker` would wrap a method, and two things
happen *between* calls that a decorator can't see: the attempt row is updated before each retry, and
the total deadline is checked before each call and again after each backoff. So `Retry.Context` is
driven by hand, and the breaker and limiter are called directly. It's more code than an annotation,
and every step of it is visible and tested.

### Timeouts and the time limiter

```yaml
payments.processor.connect-timeout: 500ms
payments.processor.read-timeout: 2s
resilience4j.timelimiter.instances.processor.timeoutDuration: 2500ms
```

- **Every value is required.** `ProcessorProperties` has no defaults, because an HTTP client's defaults
  are where infinite timeouts come from. A missing value fails startup.
- **The read timeout doesn't bound the whole call.** It stops at the response headers. A processor that
  sends headers and then stalls the body would hold the thread indefinitely. The time limiter is the
  backstop, at 2.5s: above connect + read so it never races them, and it cuts off a stalled body
  (`ProcessorClientTest` proves it). It was 2s in the first draft, where it would have raced the read
  timeout for no benefit.
- **A timeout is not a decline.** The attempt is `TIMEOUT`, the payment stays `PROCESSING`, and the client
  gets a `504` that names the payment and says not to pay again.
- **Found by a test, not the docs:** Spring 6.1's JDK request factory reports a read timeout as a
  `TimeoutException` inside an `IOException`, not as `HttpTimeoutException`. Without the test every
  timeout would have been recorded as `UNKNOWN`.

### Retry, bounded by a total deadline

```yaml
resilience4j.retry.instances.processor:
  maxAttempts: 3
  waitDuration: 200ms            # then 400ms
  enableExponentialBackoff: true
  enableRandomizedWait: true     # jitter, +/-50%
  retryExceptions: [ConnectFailed, TimedOut, ConnectionBroken]
payments.processor.total-deadline: 4400ms
```

- **What retries:** refused connections (nothing was sent), timeouts and broken connections (it may
  have been sent; safe only because the request id is the same on every call). **What never retries:**
  declines (the processor answered), 4xx including 429 (the same request gets the same answer), and 5xx
  (left to the breaker).
- **The deadline resolves a contradiction.** Three 2-second attempts take ~6.6s, and the brief asked
  for an error in about 2s. A call only starts if the 2.5s limiter still fits inside 4.4s. Three failed
  connects fit (attempt 3 starts by 0.5 + 0.3 + 0.5 + 0.6 = 1.9s); one read timeout doesn't
  (2.0 + 2.5 = 4.5s). So a timeout answers in ~2s and is never retried in practice, and that's also what
  stops a second charge being attempted while the first may be in flight.
- **Our own exception types, not `IOException`.** `ProcessorClient` translates every transport failure,
  so the drafted `IOException` / `TimeoutException` list would never have matched anything.
- **The first deadline was wrong:** 3.5s, with a comment claiming connect failures always get three
  attempts. Stopping the simulator showed each failure took the full 503ms connect timeout, so only two
  fit. That's why it's 4.4s.

### Circuit breaker, with thresholds chosen against a 5% background error rate

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

**The assumptions.** A realistic background error rate is about **5%**; a real outage fails close to
**100%**; traffic is low and uneven. A simulation fed each candidate one million calls at a fixed
failure rate and counted trips:

| Window / min calls / threshold | Trips per 1M calls at 5% | at 10% | at 20% | Calls before a dead processor trips it |
|---|---|---|---|---|
| **20 / 10 / 50%** (chosen) | **0** | 2 | 662 | **10** |
| 20 / 10 / 25% | 426 | 5,754 | 36,843 | 10 |
| 10 / 5 / 20% (typical blog values) | 15,928 | 48,432 | 117,610 | 5 |
| 100 / 100 / 50% (Resilience4j defaults) | 0 | 0 | 0 | 100 |

With 5% errors, P(≥10 of 20 calls fail) = 1.1 × 10⁻⁸, while P(≥1 of 10 fail) = 0.40. A threshold
that one failed call in ten can reach will trip on background noise most of the time.

- **50%:** noise is around 5% and outages close to 100%; 50% is far from both.
- **Count-based, 20:** at low, uneven volume a 60-second window would judge on 3 calls one minute and
  none the next. At high, steady volume a time-based window would be better.
- **Minimum 10:** two failures after a restart can't open it. The default of 100 would mean about 50
  failed payments before anything happened.
- **10s open:** a judgement call, and also the honest `Retry-After` given to clients.
- **3 probes:** 0 or 1 failure closes it, 2 or 3 reopen it. Resilience4j waits for all three before
  deciding, even when two have already failed; a test written to expect otherwise found that.
- **Automatic transition is mandatory here.** The fail-fast check refuses requests before any call is
  made, so without it nothing would ever arrive to move the breaker out of `OPEN`.
- **Health indicator off.** With it on, an open breaker turns `/actuator/health` `DOWN`, and an
  orchestrator pulls every instance because one *dependency* is down.
- **What counts:** connect failures, timeouts, broken connections and 5xx. Ignored: 4xx, which says
  nothing about health. Declines are return values, so they count as successes, and a run of declined
  cards can never open it.
- **The fallback:** when it's open, `POST /payments` returns `503` with `Retry-After` before anything is
  written. Nothing is created, so the same `Idempotency-Key` works once it closes. Replays still work,
  because they don't need the processor.

### Bulkhead

```yaml
resilience4j.bulkhead.instances.processor:
  maxConcurrentCalls: 20
  maxWaitDuration: 0
```

**Why it's here as well as a timeout.** A timeout bounds *one* call. A bulkhead bounds *how many* can
be in flight. A processor answering in 1.5s triggers no timeout, no error and no breaker, yet each
payment holds a request thread for 1.5s. With 200 Tomcat threads and enough incoming payments, all 200
wait on the processor, and reads that never touch it queue behind them.

- **20 is a tenth of Tomcat's threads:** about 160 payments/s at the normal 120ms, about 13/s at 1.5s.
  Slow the processor down and the payment path sheds load instead of taking the service with it.
- **No waiting for a permit**, because a request waiting for one still holds its thread.
- **A full bulkhead is the same contract as an open breaker:** `503`, `Retry-After: 1`, nothing created.
  Replays need no permit and are never refused.

### One attempt row per processor request id

A retry sends the same request id, so the processor treats it as the same authorisation: at most one
charge, however many calls it took. So it's the same attempt. `payment_attempts` has one row per
(payment, operation, `processor_request_id`), enforced by a unique constraint (V3). A retry counts the
call (`call_count`) on that row, committed before the call, so a crash mid-retry still leaves it
`PENDING`. A processor that delivers one answer twice can't create a second attempt. This reverses the
first design's "one row per call"; what it gives up is the outcome of each failed call before the
last, which is now only in the logs.

### What was deliberately not used

- **Rate limiter - the skipped pattern.** It caps calls *per time period*. Two places it could go, and
  neither is right yet:
  - *Outbound, to respect the processor's limit:* we have no published limit to set it to, and a
    number invented here would either refuse payments the processor would take, or allow ones it
    would reject. When the processor says it's overloaded it answers `429`, and that's the signal to
    act on. (Honouring its `Retry-After` is the open item below.) What actually protects us from a slow
    processor is how many calls are *waiting*, not how many *start* per second - which is the
    bulkhead's job, not a rate limiter's.
  - *Inbound, per merchant, to stop one client flooding the API:* that's a real need, but it belongs
    at the edge with authentication (Phase 6), where the merchant is known, not inside the processor
    client.
- **Thread-pool bulkhead.** Resilience4j's other bulkhead runs calls on its own pool with a queue. It
  suits asynchronous callers. Here the caller is a request thread that has to wait for the answer
  anyway, so a second pool would add a queue and a hand-off without freeing anything. The semaphore
  counts the thread that's actually held.
- **Resilience4j's cache module.** An authorisation must never be answered from a cache: two identical
  requests with different idempotency keys are two payments. The only legitimate reuse of an answer is
  the replay, and that's the database constraint in ADR-004.
- **Fallback methods.** Resilience4j's `fallbackMethod` exists to return *something* when the call
  fails. Every fallback that returns a payment outcome invents one. The fallbacks here are errors:
  `503` (refused, nothing created) and `504` (outcome unknown, here's the payment id).
- **Slow-call thresholds on the breaker.** Calls slower than 2s already become timeouts and are
  counted. Calls slower than normal but under 2s aren't, and that's a known gap. Setting a slow-call
  threshold needs measured latency percentiles, which Phase 8 will produce.

### Alternatives rejected

- **Annotations (`@Retry`, `@CircuitBreaker`, `@Bulkhead`).** Shorter, but the attempt-row writes and
  the deadline checks happen between calls, where an annotation can't reach. And their default order
  would put the bulkhead in the wrong place.
- **Library defaults for the breaker (100 / 100 / 50%).** Never flaps, but needs 100 calls to react.
- **Blog-style breaker values.** They trip constantly on 5% noise.
- **Retrying 5xx.** The brief limited retries to IO and timeouts. A 5xx may have charged, so it's
  `UNKNOWN`, and repeated 5xx are what the breaker is for.
- **Creating the payment and marking it `FAILED` when the breaker is open or the bulkhead full.** Its
  key would replay `FAILED` forever, so the client would need a new key after a brief outage.

## Consequences

**What this gives us** - each measured on the real Docker stack, not only in tests:

| Claim | Evidence |
|---|---|
| A timeout is a bounded `504`, not a `FAILED` payment | `TIMEOUT`: `504` in 2.08s, one attempt `TIMEOUT`, payment `PROCESSING`. `SLOW` at 10s: the same outcome, a different cause. |
| Retries are bounded, jittered, and use one request id | Simulator stopped: one attempt row per payment, `call_count` 3, 2.1-2.4s; the waits between calls varied ~190-450ms. |
| The breaker opens on an outage and nowhere else | `HTTP_500`: opened on the 10th payment; `503` in 13ms. 200 payments at 5% `INTERMITTENT`: no `503`s. `CircuitBreakerIT`: zero trips in 100,000 calls at 5%; the blog config trips 500+. |
| The breaker recovers by itself | `OUTAGE` (30s of `503`): `CLOSED → OPEN → HALF_OPEN → OPEN → HALF_OPEN → CLOSED`, no second curl. |
| Reads survive a slow processor | Processor at 1.5s, 300 clients, 15s: read p95 **61ms** with the bulkhead (35ms with no load at all), **1,119ms** without it. Exactly 200 payments succeeded: 20 permits × 15s / 1.5s. |
| A duplicated answer is one attempt | `DUPLICATE_RESPONSE`: three payments, three rows, `call_count` 2 each. The database refuses a second row for the same request id. |

Virtual threads were measured too: with no bulkhead, reads stayed fast (p95 51ms), but all 300 calls
went to the processor at once. They fix thread exhaustion; they don't bound the load on the processor
or the 10-connection pool. The bulkhead stays.

**What this makes hard**

- **The numbers encode assumptions about traffic.** At ten times the volume a 20-call window covers a
  fraction of a second, and a time-based window becomes right. Phase 8 redoes them under load.
- **The deadline sums must be redone whenever a timeout changes.** They're written next to the values
  in `application.yml` for that reason.
- **Timeouts are never retried in practice.** That's safe, but a transient timeout fails the request.
  Retrying one would need a read timeout shorter than half the deadline, which would turn normal slow
  answers into duplicate calls.
- **Narrow races.** A request that passes the breaker or bulkhead check just before it closes off is
  refused at the call and ends `FAILED` (`CIRCUIT_OPEN`), and its key replays that. A fourth request
  during `HALF_OPEN` does the same.
- **One breaker and one bulkhead for the whole processor.** One failing card scheme would open it for
  everyone.
- **An attempt row keeps only its last call.** Earlier failed calls' outcomes are in the logs.
- **Unknown outcomes stay `PROCESSING`.** Nothing reconciles them yet.

**Found on the real stack, not by the tests.** The first `DUPLICATE_RESPONSE` threw an exception after
sending half the body, expecting Tomcat to drop the connection. Spring Boot's error handling appended
its own JSON instead, and the client got a complete, garbled body, which it correctly called malformed
and didn't retry. The tests passed because the in-process fake processor closes connections
differently. The simulator now declares the full length, sends half and asks Tomcat to close the
connection. Three bugs in this phase were caught like this, by a real server disagreeing with an
assumption: the Spring timeout type, the connect-failure timing, and this one.

## Status of the pieces

| Piece | State |
|---|---|
| Connect/read timeouts, time limiter backstop | Built, tested, real stack |
| Retry with a total deadline, one request id | Built, tested, real stack |
| Circuit breaker, thresholds as above, fail-fast `503` | Built, tested, real stack |
| Bulkhead (20, no wait), fail-fast `503` | Built, tested (`BulkheadIT`), measured on the real stack |
| One attempt row per processor request id | Built (V3), tested, real stack |
| `/actuator/circuitbreakers`, `circuitbreakerevents`, `bulkheads` | Exposed, read-only. Exposing `circuitbreakers` had also exposed an unauthenticated write (`FORCE_OPEN`, `DISABLE`), found on 2026-11-15 by trying it; `ReadOnlyActuatorFilter` now refuses every non-`GET` under `/actuator` with `405` until auth (Phase 6) |
| Simulator: all eight modes | Built, each reproducible in one curl (README, Failure handling) |
| Rate limiter | Not used, deliberately; inbound per-merchant limiting belongs with auth (Phase 6) |
| Honouring a processor `429`'s `Retry-After` | Not built: a `429` fails the payment |
| Slow-call threshold | Not set; needs measured percentiles (Phase 8) |
| Thresholds and limits revisited under real load | Phase 8 |
| Reconciliation of `PROCESSING` payments | Not built |

## Revision notes

**2026-11-05.** Written as "Circuit breaker thresholds chosen against a 5% background error rate".

**2026-11-13.** Bulkhead and attempt-model sections added.

**2026-11-15.** Rewritten as the record of the whole phase, and renamed from
`0008-circuit-breaker-thresholds.md`. Added: the five patterns and which were used, the order they wrap
each other in (and why the bulkhead is outermost), timeouts and retry, and what was deliberately not
used. The guide asked for "the two you skipped"; the honest count is one of the five core patterns
(the rate limiter), plus the thread-pool bulkhead, the cache module and fallback methods as
deliberate near-misses. The breaker thresholds and their evidence are unchanged.
