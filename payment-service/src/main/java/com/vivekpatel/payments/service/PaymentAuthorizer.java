package com.vivekpatel.payments.service;

import com.vivekpatel.payments.domain.AttemptOutcome;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.processor.AuthorizationDecision;
import com.vivekpatel.payments.processor.AuthorizeRequest;
import com.vivekpatel.payments.processor.ProcessorClient;
import com.vivekpatel.payments.processor.ProcessorException;
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
 * <p><b>Three steps, and only the outer two are transactions.</b>
 *
 * <pre>
 *   tx 1   CREATED -> PROCESSING, attempt PENDING        commit; connection back to the pool
 *          processor call                                no transaction, no connection held
 *   tx 2   attempt outcome, PROCESSING -> final status   commit
 * </pre>
 *
 * Holding a transaction across the call would hold a pooled connection, and row locks, for as long
 * as the processor takes. With a pool of 10 and a processor answering in 2 s, that is about five
 * payments a second for the whole service - including requests that never call the processor.
 * {@link Propagation#NEVER} makes wrapping this in a transaction an immediate error instead.
 *
 * <p><b>No retries yet.</b> Every attempt is the only attempt: a technical failure that certainly
 * charged nothing fails the payment, and a timeout leaves it {@code PROCESSING}. Retries, with a
 * budget and only for failures where a retry is safe, are the next step - and the request id is
 * already stable per payment so that a retry is recognisably the same authorisation.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class PaymentAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(PaymentAuthorizer.class);

    private final PaymentApplicationService payments;
    private final ProcessorClient processor;

    public PaymentAuthorizer(PaymentApplicationService payments, ProcessorClient processor) {
        this.payments = payments;
        this.processor = processor;
    }

    /**
     * @param idempotencyKey the key the payment was created with, or {@code null}; its stored
     *     response is updated to the payment as this call returns it.
     * @return the payment after the attempt: {@code AUTHORIZED}, {@code FAILED}, or
     *     {@code PROCESSING} when the outcome is unknown.
     */
    public Payment authorize(UUID paymentId, String idempotencyKey) {
        AuthorizationStarted started = payments.startAuthorization(paymentId);
        Payment payment = started.payment();
        AuthorizeRequest request = new AuthorizeRequest(
                AuthorizeRequest.requestIdFor(paymentId),
                payment.getPaymentMethodToken(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currencyCode());

        // nanoTime, not the Clock: latency needs a monotonic source, and the Clock is wall time.
        long startedAt = System.nanoTime();
        AttemptResult result = call(request);
        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);

        if (result.outcome() != AttemptOutcome.SUCCESS) {
            log.info("Authorisation of payment {} ended {} ({}) after {} ms",
                    paymentId, result.outcome(), result.errorCode(), latency.toMillis());
        }
        return payments.completeAuthorization(started.attemptId(), result, latency, idempotencyKey);
    }

    /**
     * The processor's answer, or its absence, in attempt terms. Every path returns: nothing the
     * processor does can leave the attempt without an outcome.
     *
     * <p>A decline and a refused request both end as {@link AttemptOutcome#FAILURE}, because for
     * the payment both mean "no money moved, and this attempt is over". They arrive differently -
     * the decline as a return value, the refusal as an exception - and that difference is what the
     * circuit breaker will see. The {@code error_code} keeps them apart in the table.
     */
    AttemptResult call(AuthorizeRequest request) {
        try {
            return switch (processor.authorize(request)) {
                case AuthorizationDecision.Approved approved ->
                        new AttemptResult(AttemptOutcome.SUCCESS, approved.authorizationId(), null);
                case AuthorizationDecision.Declined declined ->
                        new AttemptResult(AttemptOutcome.FAILURE, null, declined.declineCode());
            };
        } catch (ProcessorException e) {
            return switch (e) {
                case ProcessorException.NotProcessed notProcessed ->
                        new AttemptResult(AttemptOutcome.FAILURE, null, notProcessed.errorCode());
                case ProcessorException.TimedOut timedOut ->
                        new AttemptResult(AttemptOutcome.TIMEOUT, null, timedOut.errorCode());
                case ProcessorException.OutcomeUnknown unknown ->
                        new AttemptResult(AttemptOutcome.UNKNOWN, null, unknown.errorCode());
            };
        }
    }
}
