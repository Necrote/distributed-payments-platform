package com.vivekpatel.payments.domain;

import java.util.Optional;

/**
 * What one call to the processor came to. Stored as its name; the schema's CHECK constraint lists
 * the same five values.
 *
 * <p>The split that matters is between the outcomes that <i>know</i> and the ones that do not.
 * {@link #SUCCESS} and {@link #FAILURE} are answers: the processor approved, declined, or certainly
 * never acted. {@link #TIMEOUT} and {@link #UNKNOWN} are not: the request may have been processed
 * and the money may have moved, and only the processor can say.
 */
public enum AttemptOutcome {

    /** Written before the call. A row still PENDING later means we crashed mid-call. */
    PENDING,

    /** The processor approved. */
    SUCCESS,

    /**
     * Definitely not approved, and definitely no money moved: a decline (a business answer, in
     * {@code error_code}), or a request the processor refused or never received (4xx, connection
     * refused). Never retried as-is.
     */
    FAILURE,

    /** Sent, and no answer within the read timeout. Not a decline. */
    TIMEOUT,

    /** Answered, but not with anything usable - a 5xx or a response we could not read. */
    UNKNOWN;

    /**
     * The status an {@link AttemptOperation#AUTHORIZE} attempt with this outcome moves its payment
     * to, from {@link PaymentStatus#PROCESSING}. Empty means the payment stays {@code PROCESSING}.
     *
     * <p>The empty cases are the point of this method. A timeout is not a decline: marking the
     * payment {@code FAILED} would invite the customer to pay again while the first charge may have
     * gone through. It stays {@code PROCESSING} until a retry with the same request id or
     * reconciliation with the processor says what happened.
     *
     * @throws IllegalStateException for {@link #PENDING}, which is not an outcome.
     */
    public Optional<PaymentStatus> paymentStatusAfterAuthorization() {
        return switch (this) {
            case SUCCESS -> Optional.of(PaymentStatus.AUTHORIZED);
            case FAILURE -> Optional.of(PaymentStatus.FAILED);
            case TIMEOUT, UNKNOWN -> Optional.empty();
            case PENDING -> throw new IllegalStateException("PENDING is not an attempt outcome");
        };
    }
}
