package com.vivekpatel.payments.service;

import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.Payment;

/**
 * What {@code POST /payments} did. Sealed, so the controller's switch must handle both outcomes and
 * the compiler says so if a third ever appears.
 */
public sealed interface CreatePaymentResult {

    /** This call created the payment. */
    record Created(Payment payment) implements CreatePaymentResult {
    }

    /**
     * An earlier call with the same key and the same request created it; this is that call's
     * response, read back from {@code idempotency_keys}. Nothing was written.
     */
    record Replayed(PaymentResponse response) implements CreatePaymentResult {
    }
}
