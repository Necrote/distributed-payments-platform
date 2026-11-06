package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vivekpatel.payments.domain.AttemptOutcome;
import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.processor.AuthorizationDecision;
import com.vivekpatel.payments.processor.AuthorizeRequest;
import com.vivekpatel.payments.processor.ProcessorClient;
import com.vivekpatel.payments.processor.ProcessorException;
import com.vivekpatel.payments.processor.ProcessorProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What gets retried, what never does, and how each answer lands in the attempt table. Transactions
 * and the database are mocked away; {@code PaymentControllerIT} runs the same flow end to end.
 */
class PaymentAuthorizerTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC);
    private static final Duration LONGEST_CALL = Duration.ofSeconds(1);
    private static final UUID FIRST_ATTEMPT = UUID.randomUUID();
    private static final UUID SECOND_ATTEMPT = UUID.randomUUID();
    private static final UUID THIRD_ATTEMPT = UUID.randomUUID();

    // Ignores NotProcessed like production, so CIRCUIT_OPEN and 4xx never count; a fresh registry
    // per test, so one test's failures never open another's breaker.
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .waitDurationInOpenState(Duration.ofSeconds(7))
            .ignoreExceptions(ProcessorException.NotProcessed.class)
            .build());
    private final PaymentApplicationService payments = mock(PaymentApplicationService.class);
    private final ProcessorClient processor = mock(ProcessorClient.class);
    private final Payment payment = Payment.create("m-1", Money.of(12500, "INR"), "tok_test_123", null, FIXED);
    private final AuthorizeRequest request = new AuthorizeRequest(
            AuthorizeRequest.requestIdFor(payment.getId()), "tok_test_123", 12500, "INR");
    private final PaymentAuthorizer authorizer = authorizerWithDeadline(Duration.ofSeconds(10));

    @BeforeEach
    void stubTheWrites() {
        when(payments.startAuthorization(payment.getId()))
                .thenReturn(new AuthorizationStarted(payment, FIRST_ATTEMPT));
        when(payments.startNextAttempt(payment.getId())).thenReturn(SECOND_ATTEMPT, THIRD_ATTEMPT);
        when(payments.completeAuthorization(any(), any(), any(), any())).thenReturn(payment);
    }

    @Test
    void approvalOnTheFirstCallIsSuccessWithTheProcessorsReference() {
        when(processor.authorize(request)).thenReturn(new AuthorizationDecision.Approved("auth_9"));

        assertThat(authorizer.authorize(payment.getId(), "key-1")).isSameAs(payment);

        ArgumentCaptor<Duration> latency = ArgumentCaptor.forClass(Duration.class);
        verify(payments).completeAuthorization(
                eq(FIRST_ATTEMPT), eq(new AttemptResult(AttemptOutcome.SUCCESS, "auth_9", null)),
                latency.capture(), eq("key-1"));
        // Measured around the call: a mocked processor answers in well under a second.
        assertThat(latency.getValue()).isBetween(Duration.ZERO, Duration.ofSeconds(5));
        verify(payments, never()).startNextAttempt(any());
    }

    @Test
    void aDeclineIsFinalAndNeverRetried() {
        // The pitfall: retrying asks the same question again, and it is the processor working.
        when(processor.authorize(request)).thenReturn(new AuthorizationDecision.Declined("INSUFFICIENT_FUNDS"));

        authorizer.authorize(payment.getId(), null);

        verify(processor, times(1)).authorize(any());
        verifyCompletedOnFirstAttempt(new AttemptResult(AttemptOutcome.FAILURE, null, "INSUFFICIENT_FUNDS"));
    }

    @Test
    void a4xxIsFinalBecauseTheSameRequestGetsTheSameAnswer() {
        when(processor.authorize(request)).thenThrow(new ProcessorException.NotProcessed("HTTP_400", null));

        authorizer.authorize(payment.getId(), null);

        verify(processor, times(1)).authorize(any());
        verifyCompletedOnFirstAttempt(new AttemptResult(AttemptOutcome.FAILURE, null, "HTTP_400"));
    }

    @Test
    void a5xxIsUnknownAndNotRetried() {
        when(processor.authorize(request)).thenThrow(new ProcessorException.OutcomeUnknown("HTTP_500", null));

        authorizer.authorize(payment.getId(), null);

        verify(processor, times(1)).authorize(any());
        verifyCompletedOnFirstAttempt(new AttemptResult(AttemptOutcome.UNKNOWN, null, "HTTP_500"));
    }

    @Test
    void connectionFailuresAreRetriedAndEachCallGetsItsOwnAttempt() {
        when(processor.authorize(request))
                .thenThrow(new ProcessorException.ConnectFailed(null))
                .thenThrow(new ProcessorException.ConnectFailed(null))
                .thenReturn(new AuthorizationDecision.Approved("auth_9"));

        authorizer.authorize(payment.getId(), "key-1");

        verify(processor, times(3)).authorize(request);
        AttemptResult failed = new AttemptResult(AttemptOutcome.FAILURE, null, "CONNECT_FAILED");
        verify(payments).recordFailedAttempt(eq(FIRST_ATTEMPT), eq(failed), any());
        verify(payments).recordFailedAttempt(eq(SECOND_ATTEMPT), eq(failed), any());
        verify(payments).completeAuthorization(
                eq(THIRD_ATTEMPT), eq(new AttemptResult(AttemptOutcome.SUCCESS, "auth_9", null)), any(), eq("key-1"));
    }

    @Test
    void attemptsAreBoundedAndTheLastFailureDecidesThePayment() {
        when(processor.authorize(request)).thenThrow(new ProcessorException.ConnectFailed(null));

        authorizer.authorize(payment.getId(), null);

        verify(processor, times(3)).authorize(any());
        verify(payments, times(2)).recordFailedAttempt(any(), any(), any());
        verify(payments).completeAuthorization(
                eq(THIRD_ATTEMPT), eq(new AttemptResult(AttemptOutcome.FAILURE, null, "CONNECT_FAILED")),
                any(), isNull());
    }

    @Test
    void aBrokenConnectionIsRetriedWithTheSameRequestIdAndLeftUnknownIfItNeverRecovers() {
        // The dangerous retry: the first request may have been processed. Only the request id
        // being the same on every call makes it safe.
        when(processor.authorize(request)).thenThrow(new ProcessorException.ConnectionBroken(null));

        authorizer.authorize(payment.getId(), null);

        ArgumentCaptor<AuthorizeRequest> sent = ArgumentCaptor.forClass(AuthorizeRequest.class);
        verify(processor, times(3)).authorize(sent.capture());
        assertThat(sent.getAllValues()).extracting(AuthorizeRequest::requestId).containsOnly(request.requestId());
        verify(payments).completeAuthorization(
                eq(THIRD_ATTEMPT), eq(new AttemptResult(AttemptOutcome.UNKNOWN, null, "IO_ERROR")),
                any(), isNull());
    }

    @Test
    void aTimeoutIsRetriedWhenTheDeadlineHasRoomForAnotherCall() {
        when(processor.authorize(request))
                .thenThrow(new ProcessorException.TimedOut(null))
                .thenReturn(new AuthorizationDecision.Approved("auth_9"));

        authorizer.authorize(payment.getId(), null);

        verify(processor, times(2)).authorize(request);
    }

    @Test
    void noRetryStartsWithoutRoomForAWholeCallInsideTheDeadline() {
        // The rule that gives a client ~2 s and not ~6.6 s, and what keeps a second charge from
        // being attempted while the first may still be in flight.
        when(processor.authorize(request)).thenThrow(new ProcessorException.TimedOut(null));

        authorizerWithDeadline(LONGEST_CALL.minusMillis(1)).authorize(payment.getId(), null);

        verify(processor, times(1)).authorize(any());
        verify(payments, never()).startNextAttempt(any());
        verifyCompletedOnFirstAttempt(new AttemptResult(AttemptOutcome.TIMEOUT, null, "READ_TIMEOUT"));
    }

    @Test
    void aFailureWithNoBudgetLeftAnswersAtOnceInsteadOfSleepingThroughABackoffFirst() {
        // Otherwise a timeout would answer one pointless backoff late.
        when(processor.authorize(request)).thenThrow(new ProcessorException.TimedOut(null));
        PaymentAuthorizer longBackoff = authorizer(LONGEST_CALL.minusMillis(1), Duration.ofSeconds(10));

        long started = System.nanoTime();
        longBackoff.authorize(payment.getId(), null);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void theBackoffSpendsTheBudgetSoNoAttemptStartsIfItUsedUpTheRoom() {
        // Room for a call when the failure happened, but not once the 500 ms backoff has passed.
        when(processor.authorize(request)).thenThrow(new ProcessorException.ConnectFailed(null));
        PaymentAuthorizer tight = authorizer(LONGEST_CALL.plusMillis(200), Duration.ofMillis(500));

        tight.authorize(payment.getId(), null);

        verify(processor, times(1)).authorize(any());
        verify(payments, never()).startNextAttempt(any());
    }

    @Test
    void anOpenBreakerRefusesLocallySoNothingIsSentAndNothingRetried() {
        breakers.circuitBreaker("processor").transitionToOpenState();

        authorizer.authorize(payment.getId(), null);

        verify(processor, never()).authorize(any());
        verifyCompletedOnFirstAttempt(new AttemptResult(AttemptOutcome.FAILURE, null, "CIRCUIT_OPEN"));
    }

    @Test
    void onlyAnOpenBreakerRejectsCallsBecauseHalfOpenNeedsCallsToProbe() {
        CircuitBreaker breaker = breakers.circuitBreaker("processor");
        assertThat(authorizer.processorIsRejectingCalls()).isFalse();

        breaker.transitionToOpenState();
        assertThat(authorizer.processorIsRejectingCalls()).isTrue();

        breaker.transitionToHalfOpenState();
        assertThat(authorizer.processorIsRejectingCalls()).isFalse();

        breaker.transitionToForcedOpenState();
        assertThat(authorizer.processorIsRejectingCalls()).isTrue();
    }

    @Test
    void retryAfterIsTheOpenStateWait() {
        assertThat(authorizer.processorRetryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void aDeclineCountsAsASuccessForTheBreakerAndAFailureAsAFailure() {
        when(processor.authorize(request))
                .thenReturn(new AuthorizationDecision.Declined("INSUFFICIENT_FUNDS"))
                .thenThrow(new ProcessorException.OutcomeUnknown("HTTP_500", null));

        authorizer.authorize(payment.getId(), null);
        authorizer.authorize(payment.getId(), null);

        CircuitBreaker.Metrics metrics = breakers.circuitBreaker("processor").getMetrics();
        assertThat(metrics.getNumberOfSuccessfulCalls()).isOne();
        assertThat(metrics.getNumberOfFailedCalls()).isOne();
    }

    private void verifyCompletedOnFirstAttempt(AttemptResult expected) {
        verify(payments).completeAuthorization(eq(FIRST_ATTEMPT), eq(expected), any(), isNull());
        verify(payments, never()).recordFailedAttempt(any(), any(), any());
    }

    private PaymentAuthorizer authorizerWithDeadline(Duration totalDeadline) {
        return authorizer(totalDeadline, Duration.ofMillis(1));
    }

    private PaymentAuthorizer authorizer(Duration totalDeadline, Duration backoff) {
        RetryConfig retry = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(backoff)
                .retryExceptions(
                        ProcessorException.ConnectFailed.class,
                        ProcessorException.TimedOut.class,
                        ProcessorException.ConnectionBroken.class)
                .build();
        TimeLimiterConfig limiter = TimeLimiterConfig.custom().timeoutDuration(LONGEST_CALL).build();
        return new PaymentAuthorizer(
                payments,
                processor,
                RetryRegistry.of(retry),
                TimeLimiterRegistry.of(limiter),
                breakers,
                new ProcessorProperties("http://unused", Duration.ofMillis(100), Duration.ofMillis(500), totalDeadline));
    }
}
