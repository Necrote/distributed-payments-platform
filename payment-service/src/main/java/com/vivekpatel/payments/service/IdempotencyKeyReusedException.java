package com.vivekpatel.payments.service;

/**
 * The same {@code Idempotency-Key} arrived with a different request body. That is a client bug, and
 * replaying the first payment would hide it - the client would believe it charged one amount while
 * the stored payment says another.
 *
 * <p>TODO Phase 2 (Day 9): map to 422 with a ProblemDetail. Until then it falls through to the
 * generic 500, which is wrong but safe: nothing is replayed and nothing is written.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used with a different request");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
