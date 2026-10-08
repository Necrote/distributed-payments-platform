package com.vivekpatel.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One row in {@code idempotency_keys}: a merchant's claim on an {@code Idempotency-Key}, and the
 * response that key will replay.
 *
 * <p>Lifecycle inside a single create transaction: {@link #claim} inserts the row with no payment
 * yet, the payment is inserted, then {@link #recordResponse} fills in what to replay. The row is
 * never updated after that transaction commits.
 *
 * <p>{@code payment_id} is a plain UUID column, not a {@code @ManyToOne}. A replay needs the stored
 * response, not the payment entity, and a relationship would invite a lazy load nobody asked for.
 * The foreign key still lives in the schema.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** {@code CHAR(64)}: see the note on {@code Payment.currency} for why this needs the type code. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** The serialised response, held as JSON text and stored as {@code JSONB}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * The table has no version column, and the id is assigned before the first save, so Spring Data
     * has nothing to tell a new row from an existing one. Without this flag {@code save()} would call
     * {@code merge()} and SELECT before every INSERT - on the hottest write path in the service.
     */
    @Transient
    private boolean isNew = true;

    /** Required by JPA. Do not use in application code. */
    protected IdempotencyKey() {
    }

    /**
     * A new, not-yet-answered claim on {@code idempotencyKey} for {@code merchantId}.
     *
     * @param requestHash SHA-256 hex of the canonical request, 64 characters.
     * @param ttl how long the key replays before the housekeeping job may delete it.
     */
    public static IdempotencyKey claim(
            String merchantId, String idempotencyKey, String requestHash, Duration ttl, Clock clock) {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(clock, "clock");

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        IdempotencyKey key = new IdempotencyKey();
        key.id = UUID.randomUUID();
        key.merchantId = merchantId;
        key.idempotencyKey = idempotencyKey;
        key.requestHash = requestHash;
        key.createdAt = now;
        key.expiresAt = now.plus(ttl);
        return key;
    }

    /** Records what a retry with this key gets back. */
    public void recordResponse(UUID paymentId, int responseStatus, String responseBody) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        this.responseStatus = responseStatus;
        this.responseBody = Objects.requireNonNull(responseBody, "responseBody");
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

    public String getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    /** {@code null} until {@link #recordResponse} has run. */
    public UUID getPaymentId() {
        return paymentId;
    }

    /** {@code null} until {@link #recordResponse} has run. */
    public Integer getResponseStatus() {
        return responseStatus;
    }

    /** {@code null} until {@link #recordResponse} has run. */
    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
