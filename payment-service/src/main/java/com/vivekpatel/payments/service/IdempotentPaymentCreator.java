package com.vivekpatel.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.IdempotencyKey;
import com.vivekpatel.payments.domain.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /payments} with replay: create the payment, or - if this merchant already used this
 * {@code Idempotency-Key} for the same request - return what the first call returned.
 *
 * <p><b>Why this is a separate, non-transactional class.</b> The duplicate key surfaces as a
 * constraint violation inside {@link PaymentApplicationService#create}'s transaction. By then that
 * transaction is finished: Spring has marked it rollback-only, and PostgreSQL itself refuses every
 * further statement on it ("current transaction is aborted, commands ignored until end of
 * transaction block"). Catching the exception <i>inside</i> {@code create} and reading the stored
 * row there cannot work - and it fails twice over: the failed {@code IdempotencyKey} is still in
 * the Hibernate session, so the lookup query's auto-flush re-sends the very INSERT that failed, and
 * the commit then throws {@code UnexpectedRollbackException}. So the catch sits here, after that
 * transaction and its session are gone, and the read runs in a transaction of its own.
 *
 * <p>The alternative is {@code REQUIRES_NEW} for the read inside the failed transaction. It works,
 * but it holds two pooled connections at once for one request - the suspended one and the new one -
 * and under load, with a pool of 10, that is how a service deadlocks on its own connection pool.
 *
 * <p>{@link Propagation#NEVER}: if a caller ever wraps this in its own transaction, the inner
 * create would join it, the violation would poison it, and the recovery read would fail. NEVER
 * turns that mistake into an immediate exception instead of a production incident.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class IdempotentPaymentCreator {

    static final String KEY_CONSTRAINT = "uq_idempotency_key_per_merchant";

    private final PaymentApplicationService payments;
    private final ObjectMapper objectMapper;

    public IdempotentPaymentCreator(PaymentApplicationService payments, ObjectMapper objectMapper) {
        this.payments = payments;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws IdempotencyKeyReusedException if the key was used before with a different request.
     */
    public CreatePaymentResult create(
            String merchantId,
            Money amount,
            String paymentMethodToken,
            String externalReference,
            String idempotencyKey) {
        try {
            return new CreatePaymentResult.Created(payments.create(
                    merchantId, amount, paymentMethodToken, externalReference, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            // Only OUR constraint means "already used". Any other integrity failure is a real
            // error and must not be turned into a lookup.
            if (idempotencyKey == null || !violates(e, KEY_CONSTRAINT)) {
                throw e;
            }
            // Two ways to get here, and both end in the same place. The first call had already
            // committed; or it was still in flight, and our INSERT waited on the unique index
            // until it committed. If it had rolled back instead, our INSERT would have
            // succeeded and we would not be in this catch.
            IdempotencyKey stored = payments.findIdempotencyKey(merchantId, idempotencyKey)
                    // The winner committed, so the row exists - unless the expiry sweep deleted it
                    // in the milliseconds between. Rare enough to fail loudly, not to handle.
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key vanished after a duplicate-key violation", e));
            String requestHash = PaymentApplicationService.requestHash(
                    merchantId, amount, paymentMethodToken, externalReference);
            PaymentResponse original = replay(stored);
            // The safety property. Same key + different body is a client bug; replaying here would
            // tell a client that sent 999 it charged 999, when the stored payment is for 12500.
            if (!stored.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException(
                        idempotencyKey, original.id(),
                        differingFields(original, amount, externalReference));
            }
            return new CreatePaymentResult.Replayed(original);
        }
    }

    /**
     * Which request fields changed, so the 422 can name them. Only the hash of the original request
     * is stored, but the original response carries {@code amount}, {@code currency} and
     * {@code externalReference}, and {@code merchantId} is equal by construction - the key is
     * scoped per merchant. The one hashed field the response never contains is the card token, so
     * if nothing visible differs, the token is what changed. It is named, never echoed.
     */
    private static List<String> differingFields(
            PaymentResponse original, Money amount, String externalReference) {
        List<String> fields = new ArrayList<>();
        if (original.amount() != amount.minorUnits()) {
            fields.add("amount");
        }
        if (!original.currency().equals(amount.currencyCode())) {
            fields.add("currency");
        }
        if (!Objects.equals(original.externalReference(), externalReference)) {
            fields.add("externalReference");
        }
        if (fields.isEmpty()) {
            fields.add("paymentMethodToken");
        }
        return fields;
    }

    /**
     * Read back through the DTO and re-serialised by Spring MVC, rather than written out as the raw
     * JSONB text. JSONB reorders object keys, so the raw text would be the same document with
     * different bytes; going through the same DTO and the same mapper reproduces the first
     * response exactly.
     */
    private PaymentResponse replay(IdempotencyKey stored) {
        try {
            return objectMapper.readValue(stored.getResponseBody(), PaymentResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is not a PaymentResponse", e);
        }
    }

    /**
     * Spring's translation keeps Hibernate's exception as the cause, and Hibernate has already
     * pulled the constraint name out of PostgreSQL's error. Matching on the name, not the
     * message text or SQLSTATE alone, is what separates "this key exists" from every other
     * unique or check constraint.
     */
    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return constraint.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        return false;
    }
}
