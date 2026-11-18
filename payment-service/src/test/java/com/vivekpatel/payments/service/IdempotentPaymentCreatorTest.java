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
import com.vivekpatel.payments.domain.PaymentStatus;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
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
    private final PaymentAuthorizer authorizer = mock(PaymentAuthorizer.class);
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    // A bulkhead of one, so a test can fill it by holding the single permit.
    private final Bulkhead bulkhead = Bulkhead.of("processor",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final IdempotentPaymentCreator creator;

    IdempotentPaymentCreatorTest() {
        bulkheads.addConfiguration("one", bulkhead.getBulkheadConfig());
        bulkheads.bulkhead("processor", "one");
        creator = new IdempotentPaymentCreator(service, authorizer, bulkheads, mapper);
    }

    private Bulkhead bulkhead() {
        return bulkheads.bulkhead("processor");
    }

    @Test
    void aFullBulkheadRefusesANewPaymentAndCreatesNothing() {
        assertThat(bulkhead().tryAcquirePermission()).isTrue();
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isInstanceOfSatisfying(TooManyPaymentsInFlightException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(1)));
        verify(service, never()).create(any(), any(), any(), any(), any());
        verify(authorizer, never()).authorize(any(), any());
    }

    @Test
    void aFullBulkheadStillReplaysAKeyThatWasAlreadyUsed() throws Exception {
        assertThat(bulkhead().tryAcquirePermission()).isTrue();
        Payment original = payment();
        IdempotencyKey stored = storedKey(
                PaymentApplicationService.requestHash("m-1", AMOUNT, "tok", "ref"), original);
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.of(stored));
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));

        assertThat(creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isEqualTo(new CreatePaymentResult.Replayed(PaymentResponse.from(original)));
        // It never took a permit, so it must not give one back either.
        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isZero();
    }

    @Test
    void thePermitIsReturnedWhenTheRequestFinishes() {
        Payment created = payment();
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenReturn(created);
        when(authorizer.authorize(created.getId(), KEY)).thenReturn(created);

        creator.create("m-1", AMOUNT, "tok", "ref", KEY);

        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isOne();
    }

    @Test
    void thePermitIsReturnedEvenWhenTheRequestFails() {
        // Otherwise every failure would leak a permit, and the bulkhead would close for good.
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isInstanceOf(IllegalStateException.class);

        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isOne();
    }

    @Test
    void firstCallCreatesThenAuthorizesAndReturnsTheAuthorizedPayment() {
        Payment created = payment();
        Payment authorized = payment();
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenReturn(created);
        when(authorizer.authorize(created.getId(), KEY)).thenReturn(authorized);

        assertThat(creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isEqualTo(new CreatePaymentResult.Created(authorized));
        verify(service, never()).findIdempotencyKey(any(), any());
    }

    @Test
    void anOpenBreakerFailsFastAndCreatesNothing() {
        when(authorizer.processorIsRejectingCalls()).thenReturn(true);
        when(authorizer.processorRetryAfter()).thenReturn(Duration.ofSeconds(10));
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isInstanceOfSatisfying(ProcessorUnavailableException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(10)));
        verify(service, never()).create(any(), any(), any(), any(), any());
        verify(authorizer, never()).authorize(any(), any());
    }

    @Test
    void anOpenBreakerFailsFastWithoutAKeyToo() {
        when(authorizer.processorIsRejectingCalls()).thenReturn(true);
        when(authorizer.processorRetryAfter()).thenReturn(Duration.ofSeconds(10));

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", null))
                .isInstanceOf(ProcessorUnavailableException.class);
        verify(service, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void anOpenBreakerStillReplaysAKeyThatWasAlreadyUsed() throws Exception {
        // A replay needs no processor, so refusing it would turn a success into an error.
        Payment original = payment();
        IdempotencyKey stored = storedKey(
                PaymentApplicationService.requestHash("m-1", AMOUNT, "tok", "ref"), original);
        when(authorizer.processorIsRejectingCalls()).thenReturn(true);
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.of(stored));
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));

        assertThat(creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isEqualTo(new CreatePaymentResult.Replayed(PaymentResponse.from(original)));
    }

    @Test
    void aPaymentLeftProcessingIsAnUnknownOutcomeNotACreated() {
        Payment created = payment();
        Payment processing = payment();
        processing.transitionTo(PaymentStatus.PROCESSING, FIXED);
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenReturn(created);
        when(authorizer.authorize(created.getId(), KEY)).thenReturn(processing);

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isInstanceOfSatisfying(PaymentOutcomeUnknownException.class, e -> {
                    assertThat(e.getPaymentId()).isEqualTo(processing.getId());
                    assertThat(e.isReplayed()).isFalse();
                });
    }

    @Test
    void replayingAnUnknownOutcomeRepeatsItRatherThanReportingSuccess() throws Exception {
        Payment original = payment();
        original.transitionTo(PaymentStatus.PROCESSING, FIXED);
        IdempotencyKey stored = IdempotencyKey.claim(
                "m-1", KEY, PaymentApplicationService.requestHash("m-1", AMOUNT, "tok", "ref"),
                Duration.ofHours(24), FIXED);
        stored.recordResponse(original.getId(), 504, mapper.writeValueAsString(PaymentResponse.from(original)));
        when(service.create("m-1", AMOUNT, "tok", "ref", KEY)).thenThrow(duplicate(IdempotentPaymentCreator.KEY_CONSTRAINT));
        when(service.findIdempotencyKey("m-1", KEY)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> creator.create("m-1", AMOUNT, "tok", "ref", KEY))
                .isInstanceOfSatisfying(PaymentOutcomeUnknownException.class, e -> {
                    assertThat(e.getPaymentId()).isEqualTo(original.getId());
                    assertThat(e.isReplayed()).isTrue();
                });
        verify(authorizer, never()).authorize(any(), any());
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
        // A retry must never become a second call to the processor.
        verify(authorizer, never()).authorize(any(), any());
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
        verify(authorizer, never()).authorize(any(), any());
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
