package com.vivekpatel.payments.domain;

/**
 * The state machine refused a transition: the payment is not in a state the requested operation
 * can start from.
 *
 * <p>A subclass of {@code IllegalStateException} so existing callers still see the type they
 * expect, but its own type so {@code ApiExceptionHandler} can map exactly this case to 409 Conflict.
 * Mapping every {@code IllegalStateException} to 409 would turn genuine bugs into client errors.
 */
public class IllegalStateTransitionException extends IllegalStateException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Illegal payment state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus getFrom() {
        return from;
    }

    public PaymentStatus getTo() {
        return to;
    }
}
