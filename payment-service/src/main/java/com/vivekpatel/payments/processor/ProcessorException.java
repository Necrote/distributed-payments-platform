package com.vivekpatel.payments.processor;

/**
 * A call to the processor that did not produce an answer. Sealed into the three things that can be
 * true afterwards, because each one means something different for the money:
 *
 * <ul>
 *   <li>{@link NotProcessed} - the processor certainly did not act. Safe to say "no money moved".</li>
 *   <li>{@link TimedOut} - the request was sent and nothing came back in time. It may have been
 *       processed.</li>
 *   <li>{@link OutcomeUnknown} - something came back, but not an answer (a 5xx, an unreadable
 *       body). It may have been processed.</li>
 * </ul>
 *
 * <p>A decline is not one of these: see {@link AuthorizationDecision}.
 */
public abstract sealed class ProcessorException extends RuntimeException {

    private final String errorCode;

    private ProcessorException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Short, stable, fits {@code payment_attempts.error_code}: {@code HTTP_503}, {@code CONNECT_FAILED}. */
    public String errorCode() {
        return errorCode;
    }

    /**
     * The processor refused the request (any 4xx, including 429) or never received it (connection
     * refused or not established in time). Nothing was charged.
     *
     * <p>Not all of these are worth retrying: a 400 says our request is wrong and will be wrong
     * again; a 429 or a refused connection may pass later.
     */
    public static final class NotProcessed extends ProcessorException {
        public NotProcessed(String errorCode, Throwable cause) {
            super(errorCode, "Processor did not process the request: " + errorCode, cause);
        }
    }

    /** Sent, and no response within the read timeout. Not a decline - the charge may exist. */
    public static final class TimedOut extends ProcessorException {
        public TimedOut(Throwable cause) {
            super("READ_TIMEOUT", "No response from the processor within the read timeout", cause);
        }
    }

    /** A response that is not an answer: a 5xx, or a body we cannot read. The charge may exist. */
    public static final class OutcomeUnknown extends ProcessorException {
        public OutcomeUnknown(String errorCode, Throwable cause) {
            super(errorCode, "Processor outcome unknown: " + errorCode, cause);
        }
    }
}
