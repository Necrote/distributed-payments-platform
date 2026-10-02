package com.vivekpatel.payments.service;

import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentStatus;
import com.vivekpatel.payments.persistence.PaymentRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for the payment aggregate. Each public method is one transaction.
 *
 * <p>Takes domain types rather than API DTOs, so this layer does not depend on the HTTP contract.
 *
 * <p>No idempotency here yet. That arrives in Phase 2 as a key claim inside this same transaction.
 * A half-built check would look finished without being safe, so there is deliberately none.
 */
@Service
public class PaymentApplicationService {

    private final PaymentRepository payments;
    private final Clock clock;

    public PaymentApplicationService(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    /** Persists a new payment in {@code CREATED} and returns the saved aggregate. */
    @Transactional
    public Payment create(
            String merchantId, Money amount, String paymentMethodToken, String externalReference) {
        Payment payment =
                Payment.create(merchantId, amount, paymentMethodToken, externalReference, clock);
        return payments.save(payment);
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
}
