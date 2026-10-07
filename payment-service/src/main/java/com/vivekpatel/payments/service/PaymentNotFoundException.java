package com.vivekpatel.payments.service;

import java.util.UUID;

/**
 * No payment exists with the requested id.
 *
 * <p>Plain {@code RuntimeException}, not a Spring {@code ErrorResponseException}: the service layer
 * does not know it is behind HTTP. {@code ApiExceptionHandler} maps it to a 404 ProblemDetail.
 */
public class PaymentNotFoundException extends RuntimeException {

    private final UUID paymentId;

    public PaymentNotFoundException(UUID paymentId) {
        super("No payment with id " + paymentId);
        this.paymentId = paymentId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }
}
