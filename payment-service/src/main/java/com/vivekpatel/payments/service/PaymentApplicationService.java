package com.vivekpatel.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.AttemptOperation;
import com.vivekpatel.payments.domain.AttemptOutcome;
import com.vivekpatel.payments.domain.IdempotencyKey;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentAttempt;
import com.vivekpatel.payments.domain.PaymentStatus;
import com.vivekpatel.payments.persistence.IdempotencyKeyRepository;
import com.vivekpatel.payments.persistence.PaymentAttemptRepository;
import com.vivekpatel.payments.persistence.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
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
    private final PaymentAttemptRepository attempts;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration keyTtl;

    public PaymentApplicationService(
            PaymentRepository payments,
            IdempotencyKeyRepository idempotencyKeys,
            PaymentAttemptRepository attempts,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${payments.idempotency.key-ttl}") Duration keyTtl) {
        this.payments = payments;
        this.idempotencyKeys = idempotencyKeys;
        this.attempts = attempts;
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
     * <p>A key that already exists fails here with a {@code DataIntegrityViolationException}, and
     * this transaction rolls back. Turning that into a replay is {@link IdempotentPaymentCreator}'s
     * job, outside this transaction - see there for why it cannot be done in here.
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
                    requestHash(merchantId, amount, paymentMethodToken, externalReference);
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
     * <p><b>Two concurrent captures.</b> Both can read {@code AUTHORIZED} and both pass the state
     * machine, because each checks its own snapshot. The version column settles it: the first
     * commit moves the version on, and the second UPDATE matches zero rows and fails with
     * {@code OptimisticLockingFailureException} - mapped to 409. (If the second request only reads
     * after the first has committed, it sees {@code CAPTURED} and the state machine rejects it
     * instead. Also 409. Either way, exactly one capture.)
     *
     * <p><b>Deliberately not retried.</b> A version conflict on capture means another request
     * already changed this payment - in practice, captured it. A retry would re-read
     * {@code CAPTURED} and fail anyway; and once a processor call sits in here (Phase 3), a retry
     * loop is how a loser ends up fighting a legitimate winner for a second capture. The loser gets
     * 409 and the caller decides, usually by fetching the payment and finding it captured. Do not
     * wrap this in {@code @Retryable}.
     *
     * @throws PaymentNotFoundException if no payment has this id.
     * @throws com.vivekpatel.payments.domain.IllegalStateTransitionException if it is not
     *     {@code AUTHORIZED}.
     * @throws org.springframework.dao.OptimisticLockingFailureException at commit, if a concurrent
     *     request changed the payment after this one read it.
     */
    @Transactional
    public Payment capture(UUID paymentId) {
        // TODO Phase 3: call the processor's capture and record a payment_attempts row,
        // split around the call the same way as authorisation. Until then the transition is the
        // whole operation.
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
     * The first half of an authorisation: {@code CREATED -> PROCESSING}, and a {@code PENDING}
     * attempt row, committed together <b>before</b> the processor is called.
     *
     * <p>Committed first so that a crash during the call leaves evidence: a payment in
     * {@code PROCESSING} with a {@code PENDING} attempt says "a request may have reached the
     * processor", which is exactly what reconciliation needs to know.
     *
     * <p>The state machine also makes this the gate against double authorisation. Only a
     * {@code CREATED} payment can enter {@code PROCESSING}, and the version column settles a race
     * between two callers, so at most one authorisation per payment ever reaches the processor.
     *
     * @throws PaymentNotFoundException if no payment has this id.
     * @throws com.vivekpatel.payments.domain.IllegalStateTransitionException if it is not
     *     {@code CREATED}.
     */
    @Transactional
    public AuthorizationStarted startAuthorization(UUID paymentId, String processorRequestId) {
        Payment payment = load(paymentId);
        payment.transitionTo(PaymentStatus.PROCESSING, clock);
        int attemptNumber =
                Math.toIntExact(attempts.countByPaymentIdAndOperation(paymentId, AttemptOperation.AUTHORIZE)) + 1;
        PaymentAttempt attempt = attempts.save(PaymentAttempt.start(
                paymentId, attemptNumber, AttemptOperation.AUTHORIZE, processorRequestId, clock));
        return new AuthorizationStarted(payment, attempt.getId());
    }

    /**
     * A retry's bookkeeping: the same request id is about to be sent again, so it is the same
     * attempt, and this counts the call on its row instead of adding one. Committed before the call
     * so a crash mid-retry still leaves the row {@code PENDING}. The payment does not move.
     */
    @Transactional
    public void recordAnotherCall(UUID attemptId) {
        loadAttempt(attemptId).recordAnotherCall();
    }

    /**
     * The second half: record how the last call ended, and move the payment if the outcome says where
     * to - see {@link AttemptOutcome#paymentStatusAfterAuthorization()}. On a timeout or an unknown
     * outcome the payment stays {@code PROCESSING}.
     *
     * <p>If the payment was created with an {@code Idempotency-Key}, the stored response is
     * replaced with the one this request is about to return. A replay is the original response
     * (ADR-004), and the original response is the payment after authorisation, not the
     * {@code CREATED} snapshot written by {@link #create}. When the outcome is unknown that response
     * is a {@code 504}, and the stored status says so, so that a retry gets a 504 too.
     *
     * <p>If this transaction fails after the processor approved - the database is down, say - the
     * charge exists and this service does not know it: the payment stays {@code PROCESSING} and the
     * attempt stays {@code PENDING}. That pair is what reconciliation looks for. It is the
     * dual-write problem, and it cannot be closed from inside one service.
     */
    @Transactional
    public Payment completeAuthorization(
            UUID attemptId, AttemptResult result, Duration latency, String idempotencyKey) {
        PaymentAttempt attempt = loadAttempt(attemptId);
        attempt.complete(result.outcome(), result.processorReference(), result.errorCode(), latency);

        Payment payment = load(attempt.getPaymentId());
        Optional<PaymentStatus> target = result.outcome().paymentStatusAfterAuthorization();
        target.ifPresent(status -> payment.transitionTo(status, clock));

        if (idempotencyKey != null) {
            HttpStatus responseStatus = target.isPresent() ? HttpStatus.CREATED : HttpStatus.GATEWAY_TIMEOUT;
            // Through the unique index on (merchant_id, idempotency_key); payment_id has no index.
            idempotencyKeys.findByMerchantIdAndIdempotencyKey(payment.getMerchantId(), idempotencyKey)
                    .ifPresent(key -> key.recordResponse(
                            payment.getId(), responseStatus.value(), responseBody(payment)));
        }
        return payment;
    }

    private PaymentAttempt loadAttempt(UUID attemptId) {
        return attempts.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("No payment attempt with id " + attemptId));
    }

    /**
     * The stored idempotency row for this merchant and key, in its own read-only transaction.
     *
     * <p>Called after a create has failed on the duplicate key, so the transaction that failed is
     * already gone. A fresh transaction under READ COMMITTED takes a fresh snapshot, which is what
     * makes the winner's just-committed row visible here.
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyKey> findIdempotencyKey(String merchantId, String idempotencyKey) {
        return idempotencyKeys.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
    }

    /** SHA-256 of the canonical create request; the value stored in {@code request_hash}. */
    static String requestHash(
            String merchantId, Money amount, String paymentMethodToken, String externalReference) {
        return CanonicalRequestHash.of(new CreatePaymentFingerprint(
                merchantId,
                amount.minorUnits(),
                amount.currencyCode(),
                paymentMethodToken,
                externalReference));
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
