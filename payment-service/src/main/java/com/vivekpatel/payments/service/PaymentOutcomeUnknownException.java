package com.vivekpatel.payments.service;

import java.util.UUID;

/**
 * The payment exists, but the processor never gave a usable answer, so nobody knows yet whether the
 * money moved. Mapped to {@code 504} by {@code ApiExceptionHandler}.
 *
 * <p>An error rather than a {@code 201}, because the call did not finish its job. Carrying the
 * payment id is what keeps the error safe: the client must fetch this payment, not pay again.
 */
public class PaymentOutcomeUnknownException extends RuntimeException {

    private final UUID paymentId;
    private final boolean replayed;

    /** @param replayed {@code true} if this repeats the answer an earlier call with the same key got. */
    public PaymentOutcomeUnknownException(UUID paymentId, boolean replayed) {
        super("Outcome unknown for payment " + paymentId);
        this.paymentId = paymentId;
        this.replayed = replayed;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
