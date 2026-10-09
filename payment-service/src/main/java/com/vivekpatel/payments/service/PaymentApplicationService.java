package com.vivekpatel.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.IdempotencyKey;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentStatus;
import com.vivekpatel.payments.persistence.IdempotencyKeyRepository;
import com.vivekpatel.payments.persistence.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for the payment aggregate. Each public method is one transaction.
 *
 * <p>Takes domain types rather than API DTOs, so this layer does not depend on the HTTP contract.
 * The one exception is {@link PaymentResponse}: the idempotency row stores the response a retry
 * replays, and that body has to come from the same DTO the controller returns, or a replay would
 * differ from the original.
 */
@Service
public class PaymentApplicationService {

    private final PaymentRepository payments;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration keyTtl;

    public PaymentApplicationService(
            PaymentRepository payments,
            IdempotencyKeyRepository idempotencyKeys,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${payments.idempotency.key-ttl}") Duration keyTtl) {
        this.payments = payments;
        this.idempotencyKeys = idempotencyKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.keyTtl = keyTtl;
    }

    /**
     * Persists a new payment in {@code CREATED} and returns the saved aggregate.
     *
     * <p>With an {@code idempotencyKey}, the same transaction also inserts the key row and records
     * the response a retry should get. Without one, only the payment is written.
     *
     * <p>TODO Phase 2 (Day 8-9): a key that already exists currently fails on the UNIQUE constraint
     * and surfaces as a 500. Replay (same hash) and 422 (different hash) arrive next.
     */
    @Transactional
    public Payment create(
            String merchantId,
            Money amount,
            String paymentMethodToken,
            String externalReference,
            String idempotencyKey) {
        // ONE transaction, on purpose. "Reserve the key, commit, then create the payment" looks
        // safer and reopens the exact race the UNIQUE constraint exists to close. Between the two
        // commits the key exists with no payment behind it. A retry that lands in that gap cannot
        // tell "still in progress" from "crashed after reserving", and if the first request did
        // crash, the key is stuck with nothing to replay. The only way out is to let a retry take
        // over a key with no payment - and when the first request was merely slow, not dead, both
        // now create a payment. Two charges, one key.
        //
        // Here the key row and the payment commit together or not at all, so a key with no
        // payment is never visible to anyone. A concurrent request with the same key blocks on
        // the unique index until this transaction ends, then gets the duplicate-key violation:
        // the database, not an application-level check, decides who won.
        IdempotencyKey claim = null;
        if (idempotencyKey != null) {
            String requestHash =
                    CanonicalRequestHash.of(new CreatePaymentFingerprint(
                            merchantId,
                            amount.minorUnits(),
                            amount.currencyCode(),
                            paymentMethodToken,
                            externalReference));
            // Flushed now, not at commit, so a duplicate fails here before any payment is written.
            claim = idempotencyKeys.saveAndFlush(
                    IdempotencyKey.claim(merchantId, idempotencyKey, requestHash, keyTtl, clock));
        }

        Payment payment = payments.save(
                Payment.create(merchantId, amount, paymentMethodToken, externalReference, clock));

        if (claim != null) {
            // Managed entity: dirty checking issues the UPDATE at commit, after the payment INSERT
            // that the payment_id foreign key needs.
            claim.recordResponse(
                    payment.getId(), HttpStatus.CREATED.value(), responseBody(payment));
        }
        return payment;
    }

    /**
     * Loads a payment by id.
     *
     * <p>{@code readOnly} lets Hibernate skip dirty checking at flush and tells the driver no writes
     * are coming; it is a hint, not a lock.
     *
     * @throws PaymentNotFoundException if no payment has this id.
     */
    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return load(paymentId);
    }

    /**
     * {@code AUTHORIZED -> CAPTURED}.
     *
     * <p>No {@code save()}: the entity is managed inside this transaction, so dirty checking writes
     * the change at commit, with {@code AND version = ?} on the UPDATE.
     *
     * @throws PaymentNotFoundException if no payment has this id.
     * @throws com.vivekpatel.payments.domain.IllegalStateTransitionException if it is not
     *     {@code AUTHORIZED}.
     */
    @Transactional
    public Payment capture(UUID paymentId) {
        // TODO Phase 3 (Day 13): call the processor's capture first and record a payment_attempts
        // row. Until then the transition is the whole operation.
        // TODO Phase 2 (Day 10): two concurrent captures both pass the guard; @Version must make
        // the second commit fail, and that failure needs its own 409 mapping.
        Payment payment = load(paymentId);
        payment.transitionTo(PaymentStatus.CAPTURED, clock);
        return payment;
    }

    /**
     * {@code CAPTURED -> REFUND_PENDING}. The payment stays pending: nothing confirms a refund
     * until the processor exists.
     *
     * @throws PaymentNotFoundException if no payment has this id.
     * @throws com.vivekpatel.payments.domain.IllegalStateTransitionException if it is not
     *     {@code CAPTURED}.
     */
    @Transactional
    public Payment refund(UUID paymentId) {
        // TODO Phase 3: insert a refunds row and call the processor; REFUND_PENDING then moves to
        // REFUNDED on success or back to CAPTURED on rejection.
        Payment payment = load(paymentId);
        payment.transitionTo(PaymentStatus.REFUND_PENDING, clock);
        return payment;
    }

    /**
     * Admin-forced authorisation: {@code CREATED -> PROCESSING -> AUTHORIZED} with no processor
     * involved. It exists only so capture is reachable before Phase 3, and it still goes through
     * the state machine one step at a time.
     *
     * <p>TODO Phase 3 (Day 13): delete this, and {@code AdminPaymentController}, once the
     * processor simulator performs real authorisations.
     */
    @Transactional
    public Payment forceAuthorize(UUID paymentId) {
        Payment payment = load(paymentId);
        payment.transitionTo(PaymentStatus.PROCESSING, clock);
        payment.transitionTo(PaymentStatus.AUTHORIZED, clock);
        return payment;
    }

    private Payment load(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /**
     * Serialised with the same {@code ObjectMapper} Spring MVC uses for responses, so the stored
     * body matches what the client received on the first call.
     */
    private String responseBody(Payment payment) {
        try {
            return objectMapper.writeValueAsString(PaymentResponse.from(payment));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PaymentResponse could not be serialised", e);
        }
    }

    /**
     * What a create request means, for hashing. Field names match the {@code POST /payments} JSON,
     * and the values are the parsed ones, so whitespace and key order in the client's body never
     * reach the hash. {@code externalReference} is included even when null: omitting it and
     * sending {@code null} are the same request.
     */
    private record CreatePaymentFingerprint(
            String merchantId,
            long amount,
            String currency,
            String paymentMethodToken,
            String externalReference) {
    }
}
