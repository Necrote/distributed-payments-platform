package com.vivekpatel.payments.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Every way an authorisation call can end, over real HTTP to {@link FakeProcessor}. No Spring
 * context, so it runs in {@code mvn test}; the read timeout is 300 ms so the timeout case is quick.
 */
class ProcessorClientTest {

    private static final FakeProcessor processor = FakeProcessor.start();
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private final ProcessorClient client = clientFor(processor.baseUrl());
    private final AuthorizeRequest request = new AuthorizeRequest(
            AuthorizeRequest.requestIdFor(UUID.randomUUID()), "tok_test_123", 12500, "INR");

    @BeforeEach
    void approveByDefault() {
        processor.approveAll();
    }

    @AfterAll
    static void stop() {
        processor.close();
    }

    @Test
    void approvalCarriesTheProcessorsAuthorizationId() {
        assertThat(client.authorize(request))
                .isInstanceOfSatisfying(AuthorizationDecision.Approved.class,
                        a -> assertThat(a.authorizationId()).startsWith("auth_"));
    }

    @Test
    void sendsTheRequestIdTokenAndMinorUnitAmount() {
        client.authorize(request);

        assertThat(processor.requestBodies().getLast())
                .contains("\"requestId\":\"" + request.requestId() + "\"")
                .contains("\"paymentMethodToken\":\"tok_test_123\"")
                .contains("\"amount\":12500")
                .contains("\"currency\":\"INR\"");
    }

    @Test
    void declineIsAReturnValueNotAnException() {
        // The pitfall, pinned: a decline is the processor working. If this threw, a circuit
        // breaker wrapped around the client would count every declined card as an outage.
        processor.declineAll("INSUFFICIENT_FUNDS");

        assertThat(client.authorize(request))
                .isEqualTo(new AuthorizationDecision.Declined("INSUFFICIENT_FUNDS"));
    }

    @Test
    void serverErrorIsUnknownBecauseTheChargeMayHaveHappened() {
        processor.replyWith(500, "{\"error\":\"PROCESSOR_ERROR\"}");

        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOfSatisfying(ProcessorException.OutcomeUnknown.class,
                        e -> assertThat(e.errorCode()).isEqualTo("HTTP_500"));
    }

    @Test
    void rateLimitIsNotProcessed() {
        processor.replyWith(429, "{\"error\":\"RATE_LIMITED\"}");

        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOfSatisfying(ProcessorException.NotProcessed.class,
                        e -> assertThat(e.errorCode()).isEqualTo("HTTP_429"));
    }

    @Test
    void badRequestIsNotProcessed() {
        processor.replyWith(400, "{}");

        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOfSatisfying(ProcessorException.NotProcessed.class,
                        e -> assertThat(e.errorCode()).isEqualTo("HTTP_400"));
    }

    @Test
    void noResponseWithinTheReadTimeoutIsTimedOutAndReturnsPromptly() {
        processor.approveAfter(Duration.ofSeconds(3));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(ProcessorException.TimedOut.class);
        // The deadline is ours: we gave up at ~300 ms, not when the processor finished at 3 s.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void connectionRefusedIsNotProcessedNotATimeout() throws Exception {
        // A port that was free a moment ago and has nothing listening now.
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ProcessorClient nowhere = clientFor("http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() -> nowhere.authorize(request))
                .isInstanceOfSatisfying(ProcessorException.NotProcessed.class,
                        e -> assertThat(e.errorCode()).isEqualTo("CONNECT_FAILED"));
    }

    @Test
    void anAnswerToADifferentRequestIsUnknown() {
        processor.replyWith(200,
                "{\"requestId\":\"authorize:someone-else\",\"authorizationId\":\"auth_1\",\"status\":\"APPROVED\"}");

        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOfSatisfying(ProcessorException.OutcomeUnknown.class,
                        e -> assertThat(e.errorCode()).isEqualTo("MALFORMED_RESPONSE"));
    }

    @Test
    void unreadableBodyIsUnknown() {
        processor.replyWith(200, "not json");

        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOfSatisfying(ProcessorException.OutcomeUnknown.class,
                        e -> assertThat(e.errorCode()).isEqualTo("MALFORMED_RESPONSE"));
    }

    @Test
    void approvalWithoutAnAuthorizationIdIsUnknown() {
        // Nothing to capture against later, so it cannot count as an approval.
        processor.replyWith(200, "{\"requestId\":\"" + request.requestId() + "\",\"status\":\"APPROVED\"}");

        assertThatThrownBy(() -> client.authorize(request))
                .isInstanceOf(ProcessorException.OutcomeUnknown.class);
    }

    @Test
    void declineWithoutACodeIsStillADecline() {
        processor.replyWith(200, "{\"requestId\":\"" + request.requestId() + "\",\"status\":\"DECLINED\"}");

        assertThat(client.authorize(request)).isEqualTo(new AuthorizationDecision.Declined("UNSPECIFIED"));
    }

    @Test
    void missingTimeoutFailsAtConstructionNotAtTheFirstSlowCall() {
        assertThatThrownBy(() -> new ProcessorProperties(processor.baseUrl(), Duration.ofMillis(500), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("read-timeout");
    }

    private static ProcessorClient clientFor(String baseUrl) {
        return new ProcessorClient(
                RestClient.builder(), new ProcessorProperties(baseUrl, Duration.ofMillis(500), READ_TIMEOUT));
    }
}
