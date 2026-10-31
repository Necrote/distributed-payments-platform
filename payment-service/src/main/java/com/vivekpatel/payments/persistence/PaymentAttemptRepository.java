package com.vivekpatel.payments.persistence;

import com.vivekpatel.payments.domain.AttemptOperation;
import com.vivekpatel.payments.domain.PaymentAttempt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PaymentAttempt}. */
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    /** Served by {@code idx_payment_attempts_payment}; used to number the next attempt. */
    long countByPaymentIdAndOperation(UUID paymentId, AttemptOperation operation);
}
