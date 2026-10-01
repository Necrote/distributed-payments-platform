package com.vivekpatel.payments.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The payment lifecycle, expressed as an explicit state machine.
 *
 * <p>This enum is deliberately the most complete piece of code in the starter scaffold, because it
 * is the spine of the whole service: every write path in {@code PaymentService} must ask this class
 * whether a transition is legal before it touches the database. Nothing else in the codebase is
 * allowed to set a status directly.
 *
 * <p><b>Why a map instead of {@code if} statements?</b> Because the legal transitions are data, and
 * data can be tested exhaustively. {@code PaymentStatusTest} walks every ordered pair of states
 * (7 x 7 = 49) and asserts allowed/denied for each one, which means a mutation of this table cannot
 * survive the test suite. An {@code if/else} chain would give you the same behaviour and a much
 * weaker guarantee.
 *
 * <p>The transition table:
 *
 * <pre>
 *   CREATED         -> PROCESSING, FAILED
 *   PROCESSING      -> AUTHORIZED, FAILED
 *   AUTHORIZED      -> CAPTURED, FAILED
 *   CAPTURED        -> REFUND_PENDING
 *   REFUND_PENDING  -> REFUNDED, CAPTURED
 *   FAILED          -> (terminal)
 *   REFUNDED        -> (terminal)
 * </pre>
 *
 * <p>Two choices in that table are worth being able to defend out loud:
 *
 * <ul>
 *   <li><b>{@code AUTHORIZED} cannot go straight to {@code REFUNDED}.</b> You cannot refund money
 *       you never took. An authorisation that should not be captured is voided, which in this model
 *       is {@code AUTHORIZED -> FAILED}. Real processors distinguish void from refund precisely
 *       because the money movement is different.</li>
 *   <li><b>{@code REFUND_PENDING} can go back to {@code CAPTURED}.</b> A refund attempt that the
 *       processor rejects must not strand the payment in a pending state forever; the payment is
 *       still captured and the money is still ours. This is the transition most candidates forget,
 *       and it is why the refund path needs its own attempt record rather than a boolean flag.</li>
 * </ul>
 */
public enum PaymentStatus {

    /** Persisted by the API before any external call. The idempotency key is claimed at this point. */
    CREATED,

    /** An authorisation attempt is in flight to the processor. Outcome unknown - do not retry blindly. */
    PROCESSING,

    /** Processor approved and reserved the funds. Money has not moved yet. */
    AUTHORIZED,

    /** Funds captured. This is the first state in which the ledger records a real money movement. */
    CAPTURED,

    /** Terminal failure: declined, voided, or exhausted retries. */
    FAILED,

    /** A refund has been accepted by this service but not yet confirmed by the processor. */
    REFUND_PENDING,

    /** Terminal success-after-reversal: the refund is confirmed and the ledger is reversed. */
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<PaymentStatus, Set<PaymentStatus>> table = new EnumMap<>(PaymentStatus.class);
        table.put(CREATED, EnumSet.of(PROCESSING, FAILED));
        table.put(PROCESSING, EnumSet.of(AUTHORIZED, FAILED));
        table.put(AUTHORIZED, EnumSet.of(CAPTURED, FAILED));
        table.put(CAPTURED, EnumSet.of(REFUND_PENDING));
        table.put(REFUND_PENDING, EnumSet.of(REFUNDED, CAPTURED));
        table.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        table.put(REFUNDED, EnumSet.noneOf(PaymentStatus.class));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(table);
    }

    /**
     * @return {@code true} if moving from this state to {@code target} is legal.
     *     A transition to the same state is never legal: re-entering a state would hide a bug and
     *     would also bump the optimistic-locking version for no business reason.
     */
    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /**
     * Guard clause for service code: throws instead of returning a boolean, so a caller cannot
     * ignore the result by accident.
     *
     * @throws IllegalStateTransitionException if the transition is not in the table.
     */
    public void assertCanTransitionTo(PaymentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
    }

    /** @return the states reachable from this one, as an unmodifiable set. */
    public Set<PaymentStatus> allowedTargets() {
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.get(this));
    }

    /** @return {@code true} if no transition out of this state exists. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }
}
