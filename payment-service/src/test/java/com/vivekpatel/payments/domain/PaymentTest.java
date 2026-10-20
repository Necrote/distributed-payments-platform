package com.vivekpatel.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * The aggregate's two doors, {@link Payment#create} and {@link Payment#transitionTo}.
 *
 * <p>Written on Day 11 because PIT showed the entity had no unit test of its own: deleting the
 * state-machine guard from {@code transitionTo} survived, and so did removing the positive-amount
 * check from {@code create}. The integration tests would have caught the first over HTTP; nothing
 * at unit level did.
 */
class PaymentTest {

    private static final Instant T0 = Instant.parse("2026-10-19T09:00:00.123456789Z");
    private static final Clock AT_T0 = Clock.fixed(T0, ZoneOffset.UTC);
    private static final Clock AT_T1 = Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC);

    @Test
    void createSetsEveryFieldAndStartsInCreated() {
        Payment payment = Payment.create("merchant-1", Money.of(12500, "INR"), "tok", "order-1", AT_T0);

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getMerchantId()).isEqualTo("merchant-1");
        assertThat(payment.getAmount()).isEqualTo(Money.of(12500, "INR"));
        assertThat(payment.getPaymentMethodToken()).isEqualTo("tok");
        assertThat(payment.getExternalReference()).isEqualTo("order-1");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        // Truncated to microseconds - what PostgreSQL TIMESTAMPTZ will hand back on a re-read.
        assertThat(payment.getCreatedAt()).isEqualTo(Instant.parse("2026-10-19T09:00:00.123456Z"));
        assertThat(payment.getUpdatedAt()).isEqualTo(payment.getCreatedAt());
        assertThat(payment.getVersion()).as("null until persisted, so save() persists").isNull();
    }

    @Test
    void createRejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> Payment.create("m", Money.of(0, "INR"), "tok", null, AT_T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> Payment.create("m", Money.of(-1, "INR"), "tok", null, AT_T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createAcceptsTheSmallestPositiveAmount() {
        // The boundary on the other side: 1 minor unit is a real payment.
        assertThat(Payment.create("m", Money.of(1, "INR"), "tok", null, AT_T0).getAmount().minorUnits())
                .isOne();
    }

    @Test
    void legalTransitionMovesStatusAndUpdatedAtOnly() {
        Payment payment = Payment.create("m", Money.of(100, "INR"), "tok", null, AT_T0);
        Instant created = payment.getCreatedAt();

        payment.transitionTo(PaymentStatus.PROCESSING, AT_T1);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.getUpdatedAt()).isEqualTo(T0.plusSeconds(60).truncatedTo(ChronoUnit.MICROS));
        assertThat(payment.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void illegalTransitionIsRefusedAndChangesNothing() {
        // The guard PIT showed could be deleted unnoticed. CREATED -> CAPTURED skips authorisation.
        Payment payment = Payment.create("m", Money.of(100, "INR"), "tok", null, AT_T0);
        Instant updated = payment.getUpdatedAt();

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.CAPTURED, AT_T1))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getUpdatedAt()).as("a refused move must not touch the row").isEqualTo(updated);
    }
}
