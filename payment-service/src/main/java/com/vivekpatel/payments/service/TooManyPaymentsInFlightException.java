package com.vivekpatel.payments.service;

import java.time.Duration;

/**
 * The bulkhead is full: as many payments as allowed are already waiting on the processor, so this
 * one was refused before anything was created or sent. Mapped to {@code 503} with
 * {@code Retry-After} by {@code ApiExceptionHandler}.
 *
 * <p>Refusing is the point. The alternative is queueing, and a queued request holds a thread while
 * it waits - which is the exhaustion the bulkhead exists to prevent.
 */
public class TooManyPaymentsInFlightException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyPaymentsInFlightException(Duration retryAfter) {
        super("Too many payments in flight; retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
