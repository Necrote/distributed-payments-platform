package com.vivekpatel.payments.service;

import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
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
        return payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
