package com.vivekpatel.simulator;

/**
 * The failure conditions this fake processor can produce on demand.
 *
 * <p>The simulator has no business value. Its entire purpose is to be an external dependency that
 * misbehaves <em>reproducibly</em>, so that every resilience claim in the README is backed by a
 * scenario anyone can re-run in thirty seconds. A processor that always returns 200 teaches
 * nothing; a processor you can put into {@code TIMEOUT} with one curl teaches the whole of Phase 3.
 *
 * <p>Each mode below names the thing it is there to prove. That mapping is the interview answer.
 */
public enum FailureMode {

    /** 200 with an approval, after a small realistic delay. The control case for every experiment. */
    NORMAL("Approves after the configured base latency"),

    /**
     * Responds successfully but slowly (configured {@code slow-latency}, typically just under the
     * client timeout). This is the dangerous one: nothing errors, so nothing alerts, while request
     * threads and database connections are held open and the pool drains. It is the shape of the
     * incident behind the HikariCP experiment in Phase 8.
     */
    SLOW("Approves, but slowly enough to hold resources"),

    /**
     * Never responds (sleeps past any sane client timeout). Proves that the CLIENT must own the
     * deadline: without an explicit read timeout the caller waits as long as the server feels like,
     * and "the downstream was slow" becomes "we were down".
     *
     * <p>Also the source of the hardest correctness question in the project: after a timeout you do
     * not know whether the charge happened, which is why {@code payment_attempts.outcome} has an
     * {@code UNKNOWN} value and why retrying is only safe with an idempotency key.
     */
    TIMEOUT("Never responds - the caller must enforce its own deadline"),

    /**
     * Returns HTTP 500. A transient server-side failure: retryable in principle, and the mode used
     * to demonstrate a retry storm with the circuit breaker disabled, then the same load with it
     * enabled.
     */
    HTTP_500("Server error - retryable, and the raw material for a retry storm"),

    /**
     * Returns HTTP 429 with {@code Retry-After}. Not the same as a 500: the dependency is telling
     * you its capacity limit, so the correct response is to back off (and to have a rate limiter of
     * your own), not to retry harder. Treating 429 like 500 is how a degraded dependency becomes a
     * dead one.
     */
    HTTP_429("Rate limited - back off, do not retry harder"),

    /**
     * Fails a configured percentage of calls at random ({@code intermittent-failure-rate}). The
     * realistic steady state of any real integration, and the mode that makes circuit-breaker
     * thresholds a measured decision rather than a copied default: a breaker that opens on a 5%
     * background error rate is worse than no breaker at all.
     */
    INTERMITTENT("Fails a configured fraction of calls at random"),

    /**
     * Processes the request but returns the response twice (or replays the previous response for a
     * repeated request id). Proves the payment service is idempotent at the PROCESSOR boundary too,
     * not only at the client boundary - a duplicate authorisation response must not create a second
     * attempt record or a second ledger movement.
     */
    DUPLICATE_RESPONSE("Sends the same response twice - the caller must deduplicate"),

    /**
     * Refuses every connection for a configured window, then recovers. This is the full circuit
     * breaker lifecycle in one scenario: CLOSED, OPEN once the failure rate crosses the threshold,
     * HALF_OPEN after the wait duration, and CLOSED again when the probe calls succeed. Watch the
     * state transitions in {@code /actuator/circuitbreakers} while it runs.
     */
    OUTAGE("Refuses connections for a window, then recovers");

    private final String description;

    FailureMode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
