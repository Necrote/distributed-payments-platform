package com.vivekpatel.payments.processor;

import java.util.UUID;

/**
 * Body of the processor's {@code POST /authorize}. {@code amount} is in minor units.
 *
 * @param requestId the processor-side idempotency key - see {@link #requestIdFor}.
 */
public record AuthorizeRequest(
        String requestId, String paymentMethodToken, long amount, String currency) {

    /**
     * One request id per payment's authorisation, not per attempt. If a retry sent a new id, the
     * processor would see a new authorisation, and a retry after a timeout - when the first may
     * have succeeded - would be a second charge.
     */
    public static String requestIdFor(UUID paymentId) {
        return "authorize:" + paymentId;
    }
}
