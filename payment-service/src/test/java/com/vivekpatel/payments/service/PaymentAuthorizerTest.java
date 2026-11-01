package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vivekpatel.payments.domain.AttemptOutcome;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.processor.AuthorizationDecision;
import com.vivekpatel.payments.processor.AuthorizeRequest;
import com.vivekpatel.payments.processor.ProcessorClient;
import com.vivekpatel.payments.processor.ProcessorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * How each processor answer, and each absence of one, lands in the attempt table. Transactions and
 * the database are mocked away; {@code PaymentControllerIT} runs the same flow end to end.
 */
class PaymentAuthorizerTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);

    private final PaymentApplicationService payments = mock(PaymentApplicationService.class);
    private final ProcessorClient processor = mock(ProcessorClient.class);
    private final PaymentAuthorizer authorizer = new PaymentAuthorizer(payments, processor);
    private final AuthorizeRequest request =
            new AuthorizeRequest("authorize:p-1", "tok", 12500, "INR");

    @Test
    void approvalIsSuccessWithTheProcessorsReference() {
        when(processor.authorize(request)).thenReturn(new AuthorizationDecision.Approved("auth_1"));

        assertThat(authorizer.call(request))
                .isEqualTo(new AttemptResult(AttemptOutcome.SUCCESS, "auth_1", null));
    }

    @Test
    void declineIsFailureWithTheDeclineCode() {
        when(processor.authorize(request)).thenReturn(new AuthorizationDecision.Declined("INSUFFICIENT_FUNDS"));

        assertThat(authorizer.call(request))
                .isEqualTo(new AttemptResult(AttemptOutcome.FAILURE, null, "INSUFFICIENT_FUNDS"));
    }

    @Test
    void notProcessedIsFailureBecauseNothingWasCharged() {
        when(processor.authorize(request)).thenThrow(new ProcessorException.NotProcessed("HTTP_429", null));

        assertThat(authorizer.call(request))
                .isEqualTo(new AttemptResult(AttemptOutcome.FAILURE, null, "HTTP_429"));
    }

    @Test
    void timeoutIsTimeoutNotFailure() {
        when(processor.authorize(request)).thenThrow(new ProcessorException.TimedOut(null));

        assertThat(authorizer.call(request))
                .isEqualTo(new AttemptResult(AttemptOutcome.TIMEOUT, null, "READ_TIMEOUT"));
    }

    @Test
    void serverErrorIsUnknownNotFailure() {
        when(processor.authorize(request)).thenThrow(new ProcessorException.OutcomeUnknown("HTTP_500", null));

        assertThat(authorizer.call(request))
                .isEqualTo(new AttemptResult(AttemptOutcome.UNKNOWN, null, "HTTP_500"));
    }

    @Test
    void authorizeSendsThePaymentAndClosesTheAttemptItOpened() {
        Payment payment = Payment.create("m-1", Money.of(12500, "INR"), "tok_test_123", null, FIXED);
        UUID attemptId = UUID.randomUUID();
        when(payments.startAuthorization(payment.getId()))
                .thenReturn(new AuthorizationStarted(payment, attemptId));
        AuthorizeRequest expected = new AuthorizeRequest(
                AuthorizeRequest.requestIdFor(payment.getId()), "tok_test_123", 12500, "INR");
        when(processor.authorize(expected)).thenReturn(new AuthorizationDecision.Approved("auth_9"));
        when(payments.completeAuthorization(eq(attemptId), any(), any(), eq("key-1"))).thenReturn(payment);

        assertThat(authorizer.authorize(payment.getId(), "key-1")).isSameAs(payment);
        ArgumentCaptor<Duration> latency = ArgumentCaptor.forClass(Duration.class);
        verify(payments).completeAuthorization(
                eq(attemptId), eq(new AttemptResult(AttemptOutcome.SUCCESS, "auth_9", null)),
                latency.capture(), eq("key-1"));
        // Measured around the call: a mocked processor answers in well under a second.
        assertThat(latency.getValue()).isBetween(Duration.ZERO, Duration.ofSeconds(5));
    }

    @Test
    void theRequestIdIsStablePerPaymentSoARetryIsTheSameRequest() {
        UUID paymentId = UUID.randomUUID();

        assertThat(AuthorizeRequest.requestIdFor(paymentId))
                .isEqualTo(AuthorizeRequest.requestIdFor(paymentId))
                .contains(paymentId.toString());
    }
}
