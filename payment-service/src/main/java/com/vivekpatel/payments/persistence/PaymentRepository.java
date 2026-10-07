package com.vivekpatel.payments.persistence;

import com.vivekpatel.payments.domain.Payment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the {@link Payment} aggregate. No custom queries yet: add them when an endpoint
 * needs one, not before.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
}
