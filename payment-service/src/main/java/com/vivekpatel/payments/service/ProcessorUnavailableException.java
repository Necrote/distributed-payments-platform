package com.vivekpatel.payments.service;

import java.time.Duration;

/**
 * The circuit breaker is open, so the request was refused before anything was created or sent.
 * Mapped to {@code 503} with {@code Retry-After} by {@code ApiExceptionHandler}.
 *
 * <p>This is the whole fallback, on purpose. The alternative - answering with an outcome nobody
 * decided, such as "assume approved" - would ship goods for a payment that was never authorised.
 * An error the client can retry is always better than a business outcome the code made up.
 */
public class ProcessorUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public ProcessorUnavailableException(Duration retryAfter) {
        super("Payment processor unavailable; retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
