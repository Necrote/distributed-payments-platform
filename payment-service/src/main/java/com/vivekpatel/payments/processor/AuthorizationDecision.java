package com.vivekpatel.payments.processor;

/**
 * The processor's answer to an authorisation: approved or declined. Both are <b>return values</b>,
 * because both are the processor working correctly.
 *
 * <p>This is the line the circuit breaker (Phase 3) will draw on. A breaker counts exceptions; a
 * decline is never thrown, so a run of declined cards can never open the breaker and lock every
 * customer out. Technical failures are {@link ProcessorException}s and are counted.
 */
public sealed interface AuthorizationDecision {

    /** Funds reserved. {@code authorizationId} is what capture will reference. */
    record Approved(String authorizationId) implements AuthorizationDecision {
    }

    /** A business "no" - e.g. insufficient funds. Final: retrying it asks the same question again. */
    record Declined(String declineCode) implements AuthorizationDecision {
    }
}
