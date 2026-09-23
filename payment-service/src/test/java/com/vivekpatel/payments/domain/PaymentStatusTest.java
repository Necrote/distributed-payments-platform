package com.vivekpatel.payments.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The reference test for this repository: this is the standard every other test should be held to.
 *
 * <p>It is written to survive mutation testing. The expected transitions are declared here as an
 * independent literal table rather than read from {@link PaymentStatus}, and every one of the
 * 7 x 7 ordered pairs is asserted - so a mutant that deletes an entry from the production table, or
 * adds one, or inverts the {@code contains} check, kills the build. A test that only asserted the
 * six happy transitions would leave every "this must be rejected" mutant alive, which is exactly
 * the gap PIT is good at exposing.
 */
class PaymentStatusTest {

    /**
     * The allowed transitions, restated independently of the production code. If you change the
     * state machine, you must change this table too - that friction is the point. It forces the
     * change to be deliberate rather than a side effect of some other edit.
     */
    private static Set<PaymentStatus> expectedTargetsOf(PaymentStatus from) {
        return switch (from) {
            case CREATED -> EnumSet.of(PaymentStatus.PROCESSING, PaymentStatus.FAILED);
            case PROCESSING -> EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED);
            case AUTHORIZED -> EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED);
            case CAPTURED -> EnumSet.of(PaymentStatus.REFUND_PENDING);
            case REFUND_PENDING -> EnumSet.of(PaymentStatus.REFUNDED, PaymentStatus.CAPTURED);
            case FAILED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    @Test
    @DisplayName("every ordered pair of states is allowed or denied exactly as specified")
    void everyOrderedPairMatchesTheSpecification() {
        List<Executable> assertions = new ArrayList<>();
        for (PaymentStatus from : PaymentStatus.values()) {
            Set<PaymentStatus> expected = expectedTargetsOf(from);
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean shouldBeAllowed = expected.contains(to);
                assertions.add(
                        () ->
                                assertEquals(
                                        shouldBeAllowed,
                                        from.canTransitionTo(to),
                                        () -> "transition " + from + " -> " + to));
            }
        }
        assertAll(assertions);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("no state may transition to itself")
    void selfTransitionIsAlwaysRejected(PaymentStatus status) {
        assertFalse(status.canTransitionTo(status));
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("a null target is rejected rather than throwing NullPointerException")
    void nullTargetIsRejected(PaymentStatus status) {
        assertFalse(status.canTransitionTo(null));
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("allowedTargets agrees with canTransitionTo for every state")
    void allowedTargetsAgreesWithCanTransitionTo(PaymentStatus status) {
        assertEquals(expectedTargetsOf(status), status.allowedTargets());
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("allowedTargets is unmodifiable - callers cannot edit the state machine")
    void allowedTargetsIsUnmodifiable(PaymentStatus status) {
        Set<PaymentStatus> targets = status.allowedTargets();
        assertThrows(
                UnsupportedOperationException.class, () -> targets.add(PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("FAILED and REFUNDED are terminal, and nothing else is")
    void terminalStatesAreExactlyFailedAndRefunded() {
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean expectedTerminal =
                    status == PaymentStatus.FAILED || status == PaymentStatus.REFUNDED;
            assertEquals(expectedTerminal, status.isTerminal(), "isTerminal for " + status);
        }
    }

    @Test
    @DisplayName("an authorised payment is voided, never refunded - money that never moved")
    void authorizedCannotBeRefundedDirectly() {
        assertAll(
                () -> assertFalse(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.REFUNDED)),
                () ->
                        assertFalse(
                                PaymentStatus.AUTHORIZED.canTransitionTo(
                                        PaymentStatus.REFUND_PENDING)),
                () -> assertTrue(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.FAILED)));
    }

    @Test
    @DisplayName("a rejected refund returns the payment to CAPTURED rather than stranding it")
    void refundPendingCanFallBackToCaptured() {
        assertTrue(PaymentStatus.REFUND_PENDING.canTransitionTo(PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("assertCanTransitionTo throws with both states named in the message")
    void assertCanTransitionToThrowsForIllegalTransition() {
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PaymentStatus.CREATED.assertCanTransitionTo(
                                        PaymentStatus.CAPTURED));
        assertTrue(thrown.getMessage().contains("CREATED"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("CAPTURED"), thrown.getMessage());
    }

    @Test
    @DisplayName("assertCanTransitionTo is silent for a legal transition")
    void assertCanTransitionToAcceptsLegalTransition() {
        PaymentStatus.CREATED.assertCanTransitionTo(PaymentStatus.PROCESSING);
    }
}
