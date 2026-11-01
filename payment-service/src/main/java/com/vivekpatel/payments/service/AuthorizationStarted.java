package com.vivekpatel.payments.service;

import com.vivekpatel.payments.domain.Payment;
import java.util.UUID;

/**
 * What {@link PaymentApplicationService#startAuthorization} committed: the payment, now
 * {@code PROCESSING} (detached - read it, do not change it), and the {@code PENDING} attempt that
 * {@link PaymentApplicationService#completeAuthorization} will close.
 */
public record AuthorizationStarted(Payment payment, UUID attemptId) {
}
