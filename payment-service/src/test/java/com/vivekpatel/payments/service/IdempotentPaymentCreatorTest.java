package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.IdempotencyKey;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The decision logic around a failed create, with the database mocked away. The real constraint,
 * the real rollback and the real concurrency are in {@link PaymentApplicationServiceIT}.
 */
class IdempotentPaymentCreatorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-10T09:00:00Z"), ZoneOffset.UTC);
    private static final Money AMOUNT = Money.of(12500, "INR");
    private static final String KEY = "key-1";

    private final PaymentApplicationService service = mock(PaymentApplicationService.class);
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final IdempotentPaymentCreator creator = new IdempotentPaymentCreator(service, mapper);

    @Test
    void firstCallIsCreated() {
        Payment payment = payment();
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenReturn(payment);

        assertThat(creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isEqualTo(new CreatePaymentResult.Created(payment));
        verify(service, never()).findIdempotencyKey(any(), any());
    }

    @Test
    void duplicateKeyWithTheSameRequestReplaysTheStoredResponse() throws Exception {
        Payment original = payment();
        IdempotencyKey stored = storedKey(
                PaymentApplicationService.requestHash("m-1", AMOUNT, "tok", "ref"), original);
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.of(stored));

        assertThat(creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isEqualTo(new CreatePaymentResult.Replayed(PaymentResponse.from(original)));
    }

    @Test
    void duplicateKeyWithADifferentAmountIsRefusedAndNamesTheField() {
        Payment original = payment();
        storedFor(original);
        Money different = Money.of(999, "INR");
        when(service.create("m-1", different, "tok", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));

        assertThatThrownBy(() -> creator.create("m-1", different, "tok", "ref", KEY))
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class, e -> {
                    assertThat(e.getOriginalPaymentId()).isEqualTo(original.getId());
                    assertThat(e.getDifferingFields()).containsExactly("amount");
                });
    }

    @Test
    void everyVisibleDifferenceIsNamed() {
        storedFor(payment());
        Money different = Money.of(999, "USD");
        when(service.create("m-1", different, "tok", "other-ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));

        assertThatThrownBy(() -> creator.create("m-1", different, "tok", "other-ref", KEY))
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class, e ->
                        assertThat(e.getDifferingFields())
                                .containsExactly("amount", "currency", "externalReference"));
    }

    @Test
    void aChangedCardTokenIsNamedByEliminationNeverEchoed() {
        // The stored response has no token in it, so a token-only change is invisible field by
        // field - but the hash still differs, and the token is the only hashed field left.
        storedFor(payment());
        when(service.create("m-1", AMOUNT, "tok-other", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok-other", "ref", KEY))
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class, e -> {
                    assertThat(e.getDifferingFields()).containsExactly("paymentMethodToken");
                    assertThat(e.getMessage()).doesNotContain("tok-other");
                });
    }

    @Test
    void externalReferenceAddedWhereTheOriginalHadNoneIsADifference() {
        Payment original = Payment.create("m-1", AMOUNT, "tok", null, FIXED);
        IdempotencyKey stored = storedKey(
                PaymentApplicationService.requestHash("m-1", AMOUNT, "tok", null), original);
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.of(stored));
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isInstanceOfSatisfying(IdempotencyKeyReusedException.class, e ->
                        assertThat(e.getDifferingFields()).containsExactly("externalReference"));
    }

    @Test
    void anyOtherIntegrityViolationIsRethrownWithoutALookup() {
        DataIntegrityViolationException other = duplicate("payments_pkey");
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(other);

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY)).isSameAs(other);
        verify(service, never()).findIdempotencyKey(any(), any());
    }

    @Test
    void withoutAKeyAViolationIsNeverTreatedAsAReplay() {
        DataIntegrityViolationException violation = duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT);
        when(service.create("m-1", AMOUNT, "tok", "ref", null)).thenThrow(violation);

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", null)).isSameAs(violation);
        verify(service, never()).findIdempotencyKey(any(), any());
    }

    @Test
    void violationWithNoHibernateCauseIsRethrownNotAnNpe() {
        // PIT: "t != null" in the cause-chain walk could become "true" unnoticed - every test's
        // chain contained a Hibernate exception, so the walk never reached its end. A violation
        // from plain JDBC, or one Spring built without a cause, must come out unchanged.
        DataIntegrityViolationException bare = new DataIntegrityViolationException("no cause");
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(bare);

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY)).isSameAs(bare);
        verify(service, never()).findIdempotencyKey(any(), any());
    }

    private static Payment payment() {
        return Payment.create("m-1", AMOUNT, "tok", "ref", FIXED);
    }

    /** Stubs the lookup with the key row the original "m-1 / 12500 INR / tok / ref" call stored. */
    private void storedFor(Payment original) {
        IdempotencyKey stored = storedKey(
                PaymentApplicationService.requestHash("m-1", AMOUNT, "tok", "ref"), original);
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.of(stored));
    }

    private IdempotencyKey storedKey(String requestHash, Payment payment) {
        IdempotencyKey key = IdempotencyKey.claim("m-1", KEY, requestHash, Duration.ofHours(24), FIXED);
        try {
            key.recordResponse(payment.getId(), 201, mapper.writeValueAsString(PaymentResponse.from(payment)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return key;
    }

    /** The shape Spring's JPA exception translation produces for a unique violation. */
    private static DataIntegrityViolationException duplicate(String constraint) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("23505"), constraint));
    }
}
