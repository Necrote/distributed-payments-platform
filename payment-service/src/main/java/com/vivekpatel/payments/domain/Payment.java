package com.vivekpatel.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The aggregate root. One row in {@code payments}, one payment lifecycle.
 *
 * <p><b>Phase 1 TODOs are marked inline.</b> The fields and the mapping are drafted so the shape of
 * the aggregate is settled before you write behaviour; the behaviour itself is yours to write on
 * Day 3 and Day 10.
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
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Do not use in application code. */
    protected Payment() {
    }

    // TODO Phase 1 (Day 3): add a real constructor or a static factory that takes
    // (merchantId, Money, paymentMethodToken, externalReference), assigns a UUID, sets status to
    // CREATED and stamps createdAt/updatedAt. Keep every other mutator package-private or absent -
    // an aggregate you can mutate from anywhere is not an aggregate.

    /**
     * TODO Phase 1 (Day 3): the ONLY way status may change.
     *
     * <pre>
     * public void transitionTo(PaymentStatus target, Clock clock) {
     *     this.status.assertCanTransitionTo(target);
     *     this.status = target;
     *     this.updatedAt = Instant.now(clock);
     * }
     * </pre>
     *
     * Inject a {@link java.time.Clock} rather than calling {@code Instant.now()} directly, so the
     * tests you write on Day 5 can assert on timestamps without sleeping.
     */

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

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
