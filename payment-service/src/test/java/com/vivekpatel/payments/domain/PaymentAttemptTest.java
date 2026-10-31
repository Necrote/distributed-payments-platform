package com.vivekpatel.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentAttemptTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-30T09:00:00.123456789Z"), ZoneOffset.UTC);
    private static final UUID PAYMENT = UUID.randomUUID();

    @Test
    void startsPendingWithNothingRecordedYet() {
        PaymentAttempt attempt = PaymentAttempt.start(PAYMENT, 1, AttemptOperation.AUTHORIZE, FIXED);

        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.PENDING);
        assertThat(attempt.getPaymentId()).isEqualTo(PAYMENT);
        assertThat(attempt.getAttemptNumber()).isOne();
        assertThat(attempt.getOperation()).isEqualTo(AttemptOperation.AUTHORIZE);
        assertThat(attempt.getProcessorReference()).isNull();
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getLatencyMs()).isNull();
        // Microseconds, like every timestamp PostgreSQL hands back.
        assertThat(attempt.getCreatedAt()).isEqualTo(Instant.parse("2026-10-30T09:00:00.123456Z"));
        assertThat(attempt.isNew()).isTrue();
    }

    @Test
    void notNewOnceLoadedOrPersisted() {
        // Otherwise save() on a loaded attempt would try to INSERT it again.
        PaymentAttempt attempt = PaymentAttempt.start(PAYMENT, 1, AttemptOperation.AUTHORIZE, FIXED);

        attempt.markNotNew();

        assertThat(attempt.isNew()).isFalse();
        assertThat(attempt.getId()).isNotNull();
    }

    @Test
    void attemptNumbersStartAtOne() {
        assertThatThrownBy(() -> PaymentAttempt.start(PAYMENT, 0, AttemptOperation.AUTHORIZE, FIXED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeRecordsTheOutcomeReferenceAndLatency() {
        PaymentAttempt attempt = PaymentAttempt.start(PAYMENT, 1, AttemptOperation.AUTHORIZE, FIXED);

        attempt.complete(AttemptOutcome.SUCCESS, "auth_1", null, Duration.ofMillis(137));

        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(attempt.getProcessorReference()).isEqualTo("auth_1");
        assertThat(attempt.getErrorCode()).isNull();
        assertThat(attempt.getLatencyMs()).isEqualTo(137);
    }

    @Test
    void anAttemptCompletesOnceOnly() {
        // A second result would overwrite the evidence of the first.
        PaymentAttempt attempt = PaymentAttempt.start(PAYMENT, 1, AttemptOperation.AUTHORIZE, FIXED);
        attempt.complete(AttemptOutcome.TIMEOUT, null, "READ_TIMEOUT", Duration.ofSeconds(2));

        assertThatThrownBy(() -> attempt.complete(AttemptOutcome.SUCCESS, "auth_1", null, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.TIMEOUT);
    }

    @Test
    void pendingIsNotAnOutcome() {
        PaymentAttempt attempt = PaymentAttempt.start(PAYMENT, 1, AttemptOperation.AUTHORIZE, FIXED);

        assertThatThrownBy(() -> attempt.complete(AttemptOutcome.PENDING, null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAnApprovalAuthorizesTheEndOfTheGoodPath() {
        assertThat(AttemptOutcome.SUCCESS.paymentStatusAfterAuthorization()).contains(PaymentStatus.AUTHORIZED);
    }

    @Test
    void aDefiniteNoFailsThePayment() {
        assertThat(AttemptOutcome.FAILURE.paymentStatusAfterAuthorization()).contains(PaymentStatus.FAILED);
    }

    /** A timeout is not a decline: FAILED would invite a second payment while the first may be charged. */
    @ParameterizedTest
    @EnumSource(value = AttemptOutcome.class, names = {"TIMEOUT", "UNKNOWN"})
    void notKnowingLeavesThePaymentProcessing(AttemptOutcome outcome) {
        assertThat(outcome.paymentStatusAfterAuthorization()).isEmpty();
    }

    @Test
    void pendingHasNoPaymentStatus() {
        assertThatThrownBy(AttemptOutcome.PENDING::paymentStatusAfterAuthorization)
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = AttemptOutcome.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void everyTargetIsALegalMoveFromProcessing(AttemptOutcome outcome) {
        // The mapping and the state machine are two tables; this keeps them from disagreeing.
        outcome.paymentStatusAfterAuthorization().ifPresent(target ->
                assertThat(PaymentStatus.PROCESSING.canTransitionTo(target)).isTrue());
    }
}
