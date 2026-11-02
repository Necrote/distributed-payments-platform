package com.vivekpatel.payments.processor;

/**
 * A call to the processor that did not produce an answer. Sealed into what can be true afterwards,
 * because each one means something different for the money and for a retry:
 *
 * <table>
 *   <caption>What each failure means</caption>
 *   <tr><th>Type</th><th>Could money have moved?</th><th>Retryable?</th></tr>
 *   <tr><td>{@link NotProcessed}</td><td>No: a 4xx refusal</td><td>No: same request, same answer</td></tr>
 *   <tr><td>{@link ConnectFailed}</td><td>No: nothing was sent</td><td>Yes</td></tr>
 *   <tr><td>{@link TimedOut}</td><td>Maybe</td><td>Yes, with the same request id</td></tr>
 *   <tr><td>{@link ConnectionBroken}</td><td>Maybe</td><td>Yes, with the same request id</td></tr>
 *   <tr><td>{@link OutcomeUnknown}</td><td>Maybe</td><td>No: the processor answered, and not usefully</td></tr>
 * </table>
 *
 * <p>Retryable means listed under {@code resilience4j.retry} in {@code application.yml}. A decline
 * is not one of these: see {@link AuthorizationDecision}.
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

    /** The processor refused the request (any 4xx, including 429). Nothing was charged. */
    public static final class NotProcessed extends ProcessorException {
        public NotProcessed(String errorCode, Throwable cause) {
            super(errorCode, "Processor refused the request: " + errorCode, cause);
        }
    }

    /** Connection refused, or not established in time. No request bytes left, so nothing was charged. */
    public static final class ConnectFailed extends ProcessorException {
        public ConnectFailed(Throwable cause) {
            super("CONNECT_FAILED", "Could not connect to the processor", cause);
        }
    }

    /** Sent, and no response within the deadline. Not a decline - the charge may exist. */
    public static final class TimedOut extends ProcessorException {
        public TimedOut(Throwable cause) {
            super("READ_TIMEOUT", "No response from the processor within the deadline", cause);
        }
    }

    /** The connection failed after the request may have gone out. The charge may exist. */
    public static final class ConnectionBroken extends ProcessorException {
        public ConnectionBroken(Throwable cause) {
            super("IO_ERROR", "Connection to the processor broke mid-exchange", cause);
        }
    }

    /** A response that is not an answer: a 5xx, or a body we cannot read. The charge may exist. */
    public static final class OutcomeUnknown extends ProcessorException {
        public OutcomeUnknown(String errorCode, Throwable cause) {
            super(errorCode, "Processor outcome unknown: " + errorCode, cause);
        }
    }
}
