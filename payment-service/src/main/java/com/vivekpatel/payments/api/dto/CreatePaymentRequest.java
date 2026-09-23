package com.vivekpatel.payments.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /payments}.
 *
 * <pre>
 * {
 *   "merchantId": "merchant-123",
 *   "amount": 12500,
 *   "currency": "INR",
 *   "paymentMethodToken": "tok_test_123",
 *   "externalReference": "order-827361"
 * }
 * </pre>
 *
 * <p>{@code amount} is in minor units - 12500 is Rs. 125.00. A {@code long}, never a {@code double}.
 *
 * <p>The canonical serialisation of this record is what gets hashed into
 * {@code idempotency_keys.request_hash} in Phase 2, which is why it is a record with no optional
 * mutable state: the hash has to be stable for the same logical request.
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 64) String merchantId,
        @Min(1) long amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO-4217 code")
                String currency,
        @NotBlank @Size(max = 128) String paymentMethodToken,
        @Size(max = 128) String externalReference) {
}
