package com.vivekpatel.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The aggregate root. One row in {@code payments}, one payment lifecycle.
 *
 * <p>State changes go through exactly two doors: {@link #create} for birth and {@link #transitionTo}
 * for everything after. There are no setters.
 *
 * <p>Three mapping decisions that carry weight in an interview:
 *
 * <ol>
 *   <li><b>{@code @Version} from day one.</b> Two clients calling {@code POST /capture} at the same
 *       moment both read {@code AUTHORIZED}, both pass the state-machine check, and both write
 *       {@code CAPTURED} - charging once but recording twice - unless the row carries a version
 *       that the second write fails on. Adding the column later means backfilling and a migration
 *       under load; adding it now costs nothing.</li>
 *   <li><b>The status is a {@code STRING} enum, not an ordinal.</b> Ordinals break the moment
 *       someone reorders the enum, and a payments table has to stay readable in {@code psql} at
 *       3 a.m.</li>
 *   <li><b>The client-supplied identifier is not the primary key.</b> {@code externalReference} is
 *       whatever the merchant calls this payment; {@code id} is ours. Never let a caller choose
 *       your primary key.</li>
 * </ol>
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    /**
     * Money is stored as two columns and rebuilt into {@link Money} on read. Phase 1 TODO: replace
     * these with {@code @Embedded Money} once you have decided whether you want an embeddable
     * record (Hibernate 6.2+ supports record embeddables) or an {@code AttributeConverter}. Do it
     * deliberately and write down why - it is a good ADR-sized decision.
     */
    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    /**
     * The schema declares this column {@code CHAR(3)}, which PostgreSQL reports as {@code bpchar}.
     * A bare {@code String} field defaults to {@code Types#VARCHAR}, and {@code ddl-auto=validate}
     * treats CHAR and VARCHAR as different types - so without this annotation the context fails to
     * start. Every fixed-width column in this schema (the other {@code currency} columns, and
     * {@code idempotency_keys.request_hash}) needs the same treatment.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "payment_method_token", nullable = false, updatable = false)
    private String paymentMethodToken;

    @Column(name = "external_reference", updatable = false)
    private String externalReference;

    /**
     * Optimistic locking. Hibernate adds {@code AND version = ?} to every UPDATE and throws
     * {@code OptimisticLockingFailureException} when zero rows change. Do not catch that exception
     * and retry blindly - decide per operation whether a retry is safe (Day 10).
     *
     * <p>A wrapper {@code Long}, not a primitive, on purpose. The id is assigned by us before the
     * first save, so Spring Data cannot use "id is null" to tell a new entity from an existing one.
     * It falls back to the version: {@code null} means new, so {@code save()} calls
     * {@code persist()}. With a primitive {@code long} it would call {@code merge()} and issue a
     * wasted SELECT before every INSERT. Hibernate sets the version to 0 on persist.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Do not use in application code. */
    protected Payment() {
    }

    /**
     * The only way to bring a payment into existence. The id is ours, never the caller's, and every
     * payment starts life as {@link PaymentStatus#CREATED}.
     *
     * <p>Takes a {@link Clock} rather than calling {@code Instant.now()} so tests can pin time and
     * assert on timestamps without sleeping.
     */
    public static Payment create(
            String merchantId,
            Money amount,
            String paymentMethodToken,
            String externalReference,
            Clock clock) {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(paymentMethodToken, "paymentMethodToken");
        Objects.requireNonNull(clock, "clock");
        // The table has a CHECK for this too, but failing here gives a clear exception instead of a
        // constraint-violation stack trace from inside the flush.
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive, was " + amount.minorUnits());
        }

        Instant now = now(clock);
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.merchantId = merchantId;
        payment.amountMinor = amount.minorUnits();
        payment.currency = amount.currencyCode();
        payment.paymentMethodToken = paymentMethodToken;
        payment.externalReference = externalReference;
        payment.status = PaymentStatus.CREATED;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /**
     * The ONLY way status may change. The state machine decides whether the move is legal; this
     * method just applies it.
     *
     * @throws IllegalStateTransitionException if {@code target} is not reachable from the current
     *     status.
     */
    public void transitionTo(PaymentStatus target, Clock clock) {
        this.status.assertCanTransitionTo(target);
        this.status = target;
        this.updatedAt = now(clock);
    }

    /**
     * PostgreSQL {@code TIMESTAMPTZ} stores microseconds; {@code Instant.now()} can carry
     * nanoseconds. Truncating here means the object the create call returns has the same timestamps
     * a later read of the row will return.
     */
    private static Instant now(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public Money getAmount() {
        return Money.of(amountMinor, currency);
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getPaymentMethodToken() {
        return paymentMethodToken;
    }

    public String getExternalReference() {
        return externalReference;
    }

    /** {@code null} until the payment has been persisted for the first time. */
    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
