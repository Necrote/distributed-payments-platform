package com.vivekpatel.payments.api.dto;

import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Response body for every payment endpoint.
 *
 * <p>Deliberately not the JPA entity. Serialising an entity leaks the mapping (and lazy proxies)
 * into the public API contract, and every column you add becomes a breaking API change by accident.
 */
public record PaymentResponse(
        UUID id,
        String merchantId,
        long amount,
        String currency,
        PaymentStatus status,
        String externalReference,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchantId(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currencyCode(),
                payment.getStatus(),
                payment.getExternalReference(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
