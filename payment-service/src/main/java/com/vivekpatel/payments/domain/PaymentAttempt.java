package com.vivekpatel.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One row in {@code payment_attempts}: one request id sent to the processor, and what came of it.
 *
 * <p><b>One row per request id, not per call.</b> A retry sends the same request id, which the
 * processor treats as the same authorisation, so it is the same attempt: the retry counts the call
 * on this row ({@link #recordAnotherCall()}) rather than adding a row. The schema enforces it with a
 * unique (payment, operation, request id). A duplicated answer therefore cannot become a second
 * attempt, and one authorisation is never recorded as two (V3 migration).
 *
 * <p>Written <b>before</b> the call, as {@link AttemptOutcome#PENDING}, in its own committed
 * transaction; completed after. That order is the whole design. If the process dies mid-call, the
 * PENDING row is the only evidence that a request - possibly a successful charge - ever left this
 * service. Writing the row after the call would mean a crash leaves nothing to reconcile.
 *
 * <p>{@code payment_id} is a plain UUID column, not a {@code @ManyToOne}, for the same reason as
 * {@link IdempotencyKey}: nothing here needs the payment loaded through the attempt.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    /** 1 for the first attempt at an operation. Unique per (payment, operation) in the schema. */
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 16)
    private AttemptOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private AttemptOutcome outcome;

    /** The processor's id for what it did - the authorisation id on success. */
    @Column(name = "processor_reference")
    private String processorReference;

    /** A decline code, or what went wrong technically ({@code HTTP_500}, {@code CONNECT_FAILED}). */
    @Column(name = "error_code")
    private String errorCode;

    /** The idempotency key sent to the processor. One row per value, per payment and operation. */
    @Column(name = "processor_request_id", nullable = false, updatable = false)
    private String processorRequestId;

    /** Calls made with this request id, retries included. */
    @Column(name = "call_count", nullable = false)
    private int callCount;

    /** How long the last call took. */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No version column, and the id is ours: see {@link IdempotencyKey} for why this flag exists. */
    @Transient
    private boolean isNew = true;

    /** Required by JPA. Do not use in application code. */
    protected PaymentAttempt() {
    }

    /** A new attempt, about to make its first call. */
    public static PaymentAttempt start(
            UUID paymentId,
            int attemptNumber,
            AttemptOperation operation,
            String processorRequestId,
            Clock clock) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(processorRequestId, "processorRequestId");
        Objects.requireNonNull(clock, "clock");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber starts at 1, was " + attemptNumber);
        }
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = UUID.randomUUID();
        attempt.paymentId = paymentId;
        attempt.attemptNumber = attemptNumber;
        attempt.operation = operation;
        attempt.processorRequestId = processorRequestId;
        attempt.callCount = 1;
        attempt.outcome = AttemptOutcome.PENDING;
        attempt.createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return attempt;
    }

    /**
     * Counts a retry: another call with this same request id, about to be made. Committed before
     * the call, like the first, so a crash mid-retry still leaves a {@code PENDING} row. Only while
     * the attempt is open: once it has an outcome, the authorisation is over.
     *
     * @throws IllegalStateException if this attempt already has an outcome.
     */
    public void recordAnotherCall() {
        if (this.outcome != AttemptOutcome.PENDING) {
            throw new IllegalStateException("Attempt " + id + " already completed as " + this.outcome);
        }
        this.callCount++;
    }

    /**
     * Records how the attempt ended, from its last call. Once only: a second result would
     * overwrite the evidence of the first.
     *
     * @throws IllegalStateException if this attempt already has an outcome.
     * @throws IllegalArgumentException if {@code outcome} is {@link AttemptOutcome#PENDING}.
     */
    public void complete(
            AttemptOutcome outcome, String processorReference, String errorCode, Duration latency) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(latency, "latency");
        if (this.outcome != AttemptOutcome.PENDING) {
            throw new IllegalStateException("Attempt " + id + " already completed as " + this.outcome);
        }
        if (outcome == AttemptOutcome.PENDING) {
            throw new IllegalArgumentException("An attempt cannot complete as PENDING");
        }
        this.outcome = outcome;
        this.processorReference = processorReference;
        this.errorCode = errorCode;
        this.latencyMs = Math.toIntExact(latency.toMillis());
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptOperation getOperation() {
        return operation;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    /** {@code null} unless the processor gave one. */
    public String getProcessorReference() {
        return processorReference;
    }

    /** {@code null} on success. */
    public String getErrorCode() {
        return errorCode;
    }

    public String getProcessorRequestId() {
        return processorRequestId;
    }

    public int getCallCount() {
        return callCount;
    }

    /** {@code null} while PENDING. */
    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
