package com.vivekpatel.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vivekpatel.payments.processor.FakeProcessor;
import com.vivekpatel.payments.processor.ProcessorException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The circuit breaker, with the configuration from {@code application.yml}: the whole lifecycle over
 * HTTP, and the thresholds against a realistic background error rate (ADR-008). The test profile
 * shortens only {@code waitDurationInOpenState}; the window and thresholds are production values,
 * because they are what is being checked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CircuitBreakerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static FakeProcessor processor = FakeProcessor.start();

    @DynamicPropertySource
    static void processorUrl(DynamicPropertyRegistry registry) {
        registry.add("payments.processor.base-url", processor::baseUrl);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void reset() {
        processor.approveAll();
        breaker().reset();
    }

    @AfterAll
    static void stopProcessor() {
        processor.close();
    }

    /**
     * Today's "done", as a test: CLOSED -> OPEN under 500s, fail fast while open, then
     * OPEN -> HALF_OPEN on its own and HALF_OPEN -> CLOSED once the probes succeed.
     */
    @Test
    void theFullLifecycle() throws Exception {
        List<CircuitBreaker.StateTransition> transitions = new ArrayList<>();
        breaker().getEventPublisher().onStateTransition(e -> transitions.add(e.getStateTransition()));
        String merchant = "merchant-" + UUID.randomUUID();

        // A 500 is not retried, so each payment is one call, and minimumNumberOfCalls is 10.
        processor.replyWith(500, "{\"error\":\"PROCESSOR_ERROR\"}");
        for (int i = 0; i < 9; i++) {
            createPayment(merchant).andExpect(status().isGatewayTimeout());
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        createPayment(merchant).andExpect(status().isGatewayTimeout());
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        mockMvc.perform(get("/actuator/circuitbreakers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers.processor.state").value("OPEN"));

        // Open: refused before anything is written or sent.
        int callsWhileOpen = processor.requestBodies().size();
        long paymentsWhileOpen = paymentCount(merchant);
        createPayment(merchant)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
        assertThat(processor.requestBodies()).hasSize(callsWhileOpen);
        assertThat(paymentCount(merchant)).isEqualTo(paymentsWhileOpen);

        // The processor recovers. The breaker probes on its own after waitDurationInOpenState.
        processor.approveAll();
        awaitState(CircuitBreaker.State.HALF_OPEN);
        for (int i = 0; i < 3; i++) {
            createPayment(merchant).andExpect(status().isCreated());
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        assertThat(transitions).containsExactly(
                CircuitBreaker.StateTransition.CLOSED_TO_OPEN,
                CircuitBreaker.StateTransition.OPEN_TO_HALF_OPEN,
                CircuitBreaker.StateTransition.HALF_OPEN_TO_CLOSED);
    }

    @Test
    void failingProbesSendTheBreakerBackToOpenOnlyOnceAllThreeAreIn() throws Exception {
        String merchant = "merchant-" + UUID.randomUUID();
        breaker().transitionToOpenState();
        awaitState(CircuitBreaker.State.HALF_OPEN);

        processor.replyWith(500, "{\"error\":\"PROCESSOR_ERROR\"}");
        createPayment(merchant).andExpect(status().isGatewayTimeout());
        createPayment(merchant).andExpect(status().isGatewayTimeout());
        // Not yet: Resilience4j judges HALF_OPEN only once every permitted probe has finished,
        // even when two failures already settle it. First written expecting OPEN here; this
        // test is what showed otherwise.
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        createPayment(merchant).andExpect(status().isGatewayTimeout());
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void aReplayStillWorksWhileTheBreakerIsOpen() throws Exception {
        String merchant = "merchant-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        String first = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(merchant)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        breaker().transitionToOpenState();

        String replay = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(merchant)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
    }

    /**
     * The pitfall: thresholds that flap on ordinary noise. 100,000 calls at a 5% failure rate
     * through a breaker with the production configuration must never open it, and a dead
     * processor must open it within the minimum number of calls. The blog-style configuration next
     * to it shows the test can tell the difference.
     */
    @Test
    void productionThresholdsIgnore5PercentNoiseButOpenOnARealOutage() {
        CircuitBreakerConfig production = breaker().getCircuitBreakerConfig();
        assertThat(production.getSlidingWindowSize()).isEqualTo(20);
        assertThat(production.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(production.getFailureRateThreshold()).isEqualTo(50f);

        assertThat(tripsAt(production, 0.05, 100_000)).as("trips at 5% background errors").isZero();
        assertThat(callsUntilOpen(production)).as("calls a dead processor gets").isEqualTo(10);

        CircuitBreakerConfig blogStyle = CircuitBreakerConfig.from(production)
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(20).build();
        assertThat(tripsAt(blogStyle, 0.05, 100_000)).as("blog-style trips at 5%").isGreaterThan(500);
    }

    /** Feeds a fresh breaker with this config seeded random outcomes; counts CLOSED -> OPEN. */
    private static int tripsAt(CircuitBreakerConfig config, double failureRate, int calls) {
        CircuitBreaker breaker = CircuitBreaker.of("flap-check", config);
        int[] trips = {0};
        breaker.getEventPublisher().onStateTransition(e -> {
            if (e.getStateTransition() == CircuitBreaker.StateTransition.CLOSED_TO_OPEN) {
                trips[0]++;
            }
        });
        Random random = new Random(42);
        for (int i = 0; i < calls; i++) {
            if (breaker.getState() != CircuitBreaker.State.CLOSED) {
                // Count only trips out of normal operation; start each episode fresh.
                breaker.transitionToClosedState();
            }
            record(breaker, random.nextDouble() < failureRate);
        }
        return trips[0];
    }

    private static int callsUntilOpen(CircuitBreakerConfig config) {
        CircuitBreaker breaker = CircuitBreaker.of("outage-check", config);
        int calls = 0;
        while (breaker.getState() == CircuitBreaker.State.CLOSED && calls < 1000) {
            record(breaker, true);
            calls++;
        }
        return calls;
    }

    private static void record(CircuitBreaker breaker, boolean failed) {
        breaker.acquirePermission();
        if (failed) {
            breaker.onError(0, TimeUnit.NANOSECONDS, new ProcessorException.OutcomeUnknown("HTTP_500", null));
        } else {
            breaker.onSuccess(0, TimeUnit.NANOSECONDS);
        }
    }

    private CircuitBreaker breaker() {
        return circuitBreakers.circuitBreaker("processor");
    }

    private void awaitState(CircuitBreaker.State expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (breaker().getState() != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(25);
        }
        assertThat(breaker().getState()).isEqualTo(expected);
    }

    private org.springframework.test.web.servlet.ResultActions createPayment(String merchant) throws Exception {
        return mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body(merchant)));
    }

    private long paymentCount(String merchant) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant);
    }

    private static String body(String merchant) {
        return """
                {"merchantId":"%s","amount":12500,"currency":"INR","paymentMethodToken":"tok_test_123"}"""
                .formatted(merchant);
    }
}
