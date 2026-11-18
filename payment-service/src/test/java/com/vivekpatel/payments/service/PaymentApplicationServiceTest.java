package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vivekpatel.payments.api.dto.PaymentResponse;
import com.vivekpatel.payments.domain.AttemptOperation;
import com.vivekpatel.payments.domain.AttemptOutcome;
import com.vivekpatel.payments.domain.IdempotencyKey;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentAttempt;
import com.vivekpatel.payments.domain.PaymentStatus;
import com.vivekpatel.payments.persistence.IdempotencyKeyRepository;
import com.vivekpatel.payments.persistence.PaymentAttemptRepository;
import com.vivekpatel.payments.persistence.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two halves of an authorisation, with the repositories mocked: which status, which attempt
 * number, which stored response. Transactions and the real schema are in the ITs; this is the
 * logic at unit speed, where mutation testing can see it.
 */
class PaymentApplicationServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final Duration LATENCY = Duration.ofMillis(120);

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final IdempotencyKeyRepository keys = mock(IdempotencyKeyRepository.class);
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final PaymentApplicationService service =
            new PaymentApplicationService(payments, keys, attempts, mapper, FIXED, Duration.ofHours(24));

    @Test
    void startMovesToProcessingAndOpensAPendingAttempt() {
        Payment payment = stored(Payment.create("m-1", Money.of(12500, "INR"), "tok", null, FIXED));
        when(attempts.save(any(PaymentAttempt.class))).then(returnsFirstArg());

        AuthorizationStarted started = service.startAuthorization(payment.getId(), "authorize:x");

        assertThat(started.payment().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(started.attemptId()).isNotNull();
    }

    @Test
    void theAttemptIsNumberedAfterTheOnesAlreadyRecorded() {
        Payment payment = stored(Payment.create("m-1", Money.of(12500, "INR"), "tok", null, FIXED));
        when(attempts.countByPaymentIdAndOperation(payment.getId(), AttemptOperation.AUTHORIZE)).thenReturn(2L);
        PaymentAttempt[] saved = new PaymentAttempt[1];
        when(attempts.save(any(PaymentAttempt.class))).then(inv -> saved[0] = inv.getArgument(0));

        service.startAuthorization(payment.getId(), "authorize:x");

        assertThat(saved[0].getAttemptNumber()).isEqualTo(3);
        assertThat(saved[0].getOutcome()).isEqualTo(AttemptOutcome.PENDING);
        assertThat(saved[0].getOperation()).isEqualTo(AttemptOperation.AUTHORIZE);
        assertThat(saved[0].getProcessorRequestId()).isEqualTo("authorize:x");
    }

    @Test
    void successAuthorizesThePaymentAndClosesTheAttempt() {
        Payment payment = processing();
        PaymentAttempt attempt = pendingAttemptFor(payment);

        Payment result = service.completeAuthorization(
                attempt.getId(), new AttemptResult(AttemptOutcome.SUCCESS, "auth_1", null), LATENCY, null);

        assertThat(result).isSameAs(payment);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(attempt.getProcessorReference()).isEqualTo("auth_1");
        assertThat(attempt.getLatencyMs()).isEqualTo(120);
    }

    @Test
    void timeoutRecordsTheAttemptButLeavesThePaymentProcessing() {
        Payment payment = processing();
        PaymentAttempt attempt = pendingAttemptFor(payment);

        service.completeAuthorization(
                attempt.getId(), new AttemptResult(AttemptOutcome.TIMEOUT, null, "READ_TIMEOUT"), LATENCY, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.TIMEOUT);
    }

    @Test
    void theStoredReplayBecomesTheResponseThisRequestReturns() throws Exception {
        Payment payment = processing();
        PaymentAttempt attempt = pendingAttemptFor(payment);
        IdempotencyKey key = IdempotencyKey.claim("m-1", "key-1", "a".repeat(64), Duration.ofHours(24), FIXED);
        key.recordResponse(payment.getId(), 201, "{\"status\":\"CREATED\"}");
        when(keys.findByMerchantIdAndIdempotencyKey("m-1", "key-1")).thenReturn(Optional.of(key));

        Payment result = service.completeAuthorization(
                attempt.getId(), new AttemptResult(AttemptOutcome.SUCCESS, "auth_1", null), LATENCY, "key-1");

        assertThat(key.getResponseBody())
                .isEqualTo(mapper.writeValueAsString(PaymentResponse.from(result)))
                .contains("\"status\":\"AUTHORIZED\"");
        assertThat(key.getResponseStatus()).isEqualTo(201);
    }

    @Test
    void anUnknownOutcomeStoresA504SoAReplayCannotReadAsSuccess() {
        Payment payment = processing();
        PaymentAttempt attempt = pendingAttemptFor(payment);
        IdempotencyKey key = IdempotencyKey.claim("m-1", "key-1", "a".repeat(64), Duration.ofHours(24), FIXED);
        key.recordResponse(payment.getId(), 201, "{\"status\":\"CREATED\"}");
        when(keys.findByMerchantIdAndIdempotencyKey("m-1", "key-1")).thenReturn(Optional.of(key));

        service.completeAuthorization(
                attempt.getId(), new AttemptResult(AttemptOutcome.TIMEOUT, null, "READ_TIMEOUT"), LATENCY, "key-1");

        assertThat(key.getResponseStatus()).isEqualTo(504);
        assertThat(key.getResponseBody()).contains("\"status\":\"PROCESSING\"");
    }

    @Test
    void aRetryCountsTheCallOnTheSameRowAndAddsNone() {
        // Same request id, same authorisation, same attempt: nothing is saved, the row is updated.
        Payment payment = processing();
        PaymentAttempt attempt = pendingAttemptFor(payment);

        service.recordAnotherCall(attempt.getId());

        assertThat(attempt.getCallCount()).isEqualTo(2);
        assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.PENDING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(attempts, never()).save(any());
    }

    @Test
    void withoutAKeyNoIdempotencyRowIsTouched() {
        Payment payment = processing();
        PaymentAttempt attempt = pendingAttemptFor(payment);

        service.completeAuthorization(
                attempt.getId(), new AttemptResult(AttemptOutcome.FAILURE, null, "DECLINED"), LATENCY, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(keys);
    }

    @Test
    void completingAnAttemptThatDoesNotExistFailsLoudly() {
        UUID missing = UUID.randomUUID();
        when(attempts.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeAuthorization(
                        missing, new AttemptResult(AttemptOutcome.SUCCESS, "auth_1", null), LATENCY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.toString());
    }

    private Payment processing() {
        Payment payment = Payment.create("m-1", Money.of(12500, "INR"), "tok", null, FIXED);
        payment.transitionTo(PaymentStatus.PROCESSING, FIXED);
        return stored(payment);
    }

    private Payment stored(Payment payment) {
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
        return payment;
    }

    private PaymentAttempt pendingAttemptFor(Payment payment) {
        PaymentAttempt attempt = PaymentAttempt.start(
                payment.getId(), 1, AttemptOperation.AUTHORIZE, "authorize:" + payment.getId(), FIXED);
        when(attempts.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        return attempt;
    }
}
