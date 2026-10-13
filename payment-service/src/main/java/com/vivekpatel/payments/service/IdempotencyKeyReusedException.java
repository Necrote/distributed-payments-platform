package com.vivekpatel.payments.service;

import java.util.List;
import java.util.UUID;

/**
 * The same {@code Idempotency-Key} arrived with a different request body. That is a client bug, and
 * replaying the first payment would hide it - the client would believe it charged one amount while
 * the stored payment says another. Mapped to {@code 422} by {@code ApiExceptionHandler}.
 *
 * <p>Carries enough for the client to find its own bug without asking us: the payment the key
 * already belongs to, and which request fields differ from the request that created it.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;
    private final UUID originalPaymentId;
    private final List<String> differingFields;

    public IdempotencyKeyReusedException(
            String idempotencyKey, UUID originalPaymentId, List<String> differingFields) {
        super("Idempotency-Key " + idempotencyKey + " was already used for payment "
                + originalPaymentId + " with a different request; differing fields: "
                + differingFields);
        this.idempotencyKey = idempotencyKey;
        this.originalPaymentId = originalPaymentId;
        this.differingFields = List.copyOf(differingFields);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getOriginalPaymentId() {
        return originalPaymentId;
    }

    /** JSON property names, in request order. Never empty. */
    public List<String> getDifferingFields() {
        return differingFields;
    }
}
