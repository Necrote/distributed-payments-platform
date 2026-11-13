package com.vivekpatel.payments.service;

import com.vivekpatel.payments.domain.AttemptOutcome;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.processor.AuthorizationDecision;
import com.vivekpatel.payments.processor.AuthorizeRequest;
import com.vivekpatel.payments.processor.ProcessorClient;
import com.vivekpatel.payments.processor.ProcessorException;
import com.vivekpatel.payments.processor.ProcessorProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorises a {@code CREATED} payment with the processor: {@code CREATED -> PROCESSING ->
 * AUTHORIZED | FAILED}, or still {@code PROCESSING} if the outcome is unknown.
 *
 * <p><b>Only the writes are transactions.</b>
 *
 * <pre>
 *   tx     CREATED -> PROCESSING, attempt PENDING          commit; connection back to the pool
 *          processor call                                  no transaction, no connection held
 *   tx     attempt call_count + 1 (if retrying)            still PENDING
 *          backoff, processor call, same request id        still no connection held
 *   tx     attempt outcome, PROCESSING -> final status
 * </pre>
 *
 * Holding a transaction across the call would hold a pooled connection, and row locks, for as long
 * as the processor takes. With a pool of 10 and a processor answering in 2 s, that is about five
 * payments a second for the whole service - including requests that never call the processor.
 * {@link Propagation#NEVER} makes wrapping this in a transaction an immediate error instead.
 *
 * <p><b>What gets retried.</b> Only what {@code resilience4j.retry} lists, and only while a further
 * attempt could finish inside {@link ProcessorProperties#totalDeadline()}. The deadline is what
 * makes timeouts safe in practice: a timeout uses up the budget, so a client waits ~2 s rather than
 * ~6.6 s, and no second charge is attempted while the first may still be in flight. Connection
 * failures take milliseconds and do get retried.
 *
 * <p><b>Every call sends the same request id.</b> It is what lets the processor recognise a retry as
 * the same authorisation. A new id per call would turn a retry after a broken connection into a
 * second charge. The retry here is only as safe as the processor's deduplication on that id. And
 * because it is the same authorisation, it is the same attempt row: a retry counts the call, it does
 * not add a row, so a duplicated answer can never look like a second attempt.
 *
 * <p>Resilience4j's {@link Retry.Context} is driven by hand rather than through a decorator or
 * annotation, because each retry writes to the attempt row between calls and the deadline has to be
 * checked between calls.
 *
 * <p><b>Every call also goes through the circuit breaker</b>, inside the retry, so each attempt is
 * one call the breaker sees. When it is open, a call is refused locally: nothing is sent, so the
 * attempt is a {@code FAILURE} with {@code CIRCUIT_OPEN} and is not retried. Usually the caller has
 * already refused the request before creating anything (see {@link #processorIsRejectingCalls()});
 * this path is only reached if the breaker opens between that check and the call.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(PaymentAuthorizer.class);

    private final PaymentApplicationService payments;
    private final ProcessorClient processor;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Duration longestCall;
    private final Duration totalDeadline;

    public PaymentAuthorizer(
            PaymentApplicationService payments,
            ProcessorClient processor,
            RetryRegistry retries,
            TimeLimiterRegistry timeLimiters,
            CircuitBreakerRegistry circuitBreakers,
            ProcessorProperties properties) {
        this.payments = payments;
        this.processor = processor;
        this.retry = retries.retry("processor");
        this.circuitBreaker = circuitBreakers.circuitBreaker("processor");
        this.longestCall = timeLimiters.timeLimiter("processor").getTimeLimiterConfig().getTimeoutDuration();
        this.totalDeadline = properties.totalDeadline();
    }

    /**
     * @param idempotencyKey the key the payment was created with, or {@code null}; its stored
     *     response is updated to the payment as this call returns it.
     * @return the payment after the last attempt: {@code AUTHORIZED}, {@code FAILED}, or
     *     {@code PROCESSING} when the outcome is unknown.
     */
    public Payment authorize(UUID paymentId, String idempotencyKey) {
        String requestId = AuthorizeRequest.requestIdFor(paymentId);
        AuthorizationStarted started = payments.startAuthorization(paymentId, requestId);
        Payment payment = started.payment();
        AuthorizeRequest request = new AuthorizeRequest(
                requestId,
                payment.getPaymentMethodToken(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currencyCode());

        Retry.Context<Void> retryContext = retry.context();
        UUID attemptId = started.attemptId();
        // nanoTime, not the Clock: durations need a monotonic source, and the Clock is wall time.
        long startedAt = System.nanoTime();
        while (true) {
            long attemptStartedAt = System.nanoTime();
            AttemptResult result;
            ProcessorException failure = null;
            try {
                result = resultOf(callThroughBreaker(request));
                retryContext.onComplete();
            } catch (ProcessorException e) {
                result = resultOf(e);
                failure = e;
            }
            Duration latency = Duration.ofNanos(System.nanoTime() - attemptStartedAt);
            if (failure != null) {
                // The row keeps only the last call's outcome, so each failed call is logged here.
                log.info("Authorisation call for payment {} ended {} ({}) after {} ms",
                        paymentId, result.outcome(), result.errorCode(), latency.toMillis());
            }

            if (failure == null || !waitForRetry(retryContext, failure, startedAt)) {
                return payments.completeAuthorization(attemptId, result, latency, idempotencyKey);
            }
            payments.recordAnotherCall(attemptId);
        }
    }

    /**
     * Whether the breaker is refusing calls right now. Checked before a payment is created, so an
     * open breaker fails the request fast and creates nothing. A {@code HALF_OPEN} breaker is not
     * refusing: it needs calls to find out whether the processor is back.
     */
    public boolean processorIsRejectingCalls() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /** How long an open breaker waits before probing: the honest upper bound for a Retry-After. */
    public Duration processorRetryAfter() {
        return Duration.ofMillis(
                circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
    }

    /**
     * A decline returns normally, so the breaker records it as a success, which it is. Only the
     * exceptions configured under {@code recordExceptions} count against the processor.
     */
    private AuthorizationDecision callThroughBreaker(AuthorizeRequest request) {
        try {
            return circuitBreaker.executeSupplier(() -> processor.authorize(request));
        } catch (CallNotPermittedException e) {
            // Refused here, before anything was sent: certainly no money moved.
            throw new ProcessorException.NotProcessed("CIRCUIT_OPEN", e);
        }
    }

    /**
     * @return {@code true} once the backoff has passed and another attempt may start; {@code false}
     *     if the failure is final, the attempts are used up, or the deadline leaves no room.
     */
    private boolean waitForRetry(Retry.Context<Void> retryContext, ProcessorException failure, long startedAt) {
        if (!anotherAttemptFits(startedAt)) {
            return false;
        }
        try {
            // Rethrows if this failure is not retryable or the attempts are used up; otherwise
            // sleeps through the backoff, with jitter.
            retryContext.onRuntimeError(failure);
        } catch (RuntimeException finalFailure) {
            return false;
        }
        // Checked again because the backoff itself spends the budget.
        return anotherAttemptFits(startedAt);
    }

    private boolean anotherAttemptFits(long startedAt) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        return elapsed.plus(longestCall).compareTo(totalDeadline) <= 0;
    }

    /**
     * A decline and an approval are both the processor answering. A decline is
     * {@link AttemptOutcome#FAILURE} because no money moved, but it is a return value, so nothing
     * here can retry it and a circuit breaker can never count it. The {@code error_code} keeps it
     * apart from a technical failure in the table.
     */
    private static AttemptResult resultOf(AuthorizationDecision decision) {
        return switch (decision) {
            case AuthorizationDecision.Approved approved ->
                    new AttemptResult(AttemptOutcome.SUCCESS, approved.authorizationId(), null);
            case AuthorizationDecision.Declined declined ->
                    new AttemptResult(AttemptOutcome.FAILURE, null, declined.declineCode());
        };
    }

    /** The absence of an answer, in attempt terms: did money certainly not move, or might it have? */
    private static AttemptResult resultOf(ProcessorException failure) {
        AttemptOutcome outcome = switch (failure) {
            case ProcessorException.NotProcessed notProcessed -> AttemptOutcome.FAILURE;
            case ProcessorException.ConnectFailed connectFailed -> AttemptOutcome.FAILURE;
            case ProcessorException.TimedOut timedOut -> AttemptOutcome.TIMEOUT;
            case ProcessorException.ConnectionBroken broken -> AttemptOutcome.UNKNOWN;
            case ProcessorException.OutcomeUnknown unknown -> AttemptOutcome.UNKNOWN;
        };
        return new AttemptResult(outcome, null, failure.errorCode());
    }
}
