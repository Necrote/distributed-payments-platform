package com.vivekpatel.payments.domain;

/** Which processor operation a {@link PaymentAttempt} performed. Stored as its name. */
public enum AttemptOperation {
    AUTHORIZE,
    CAPTURE,
    REFUND
}
