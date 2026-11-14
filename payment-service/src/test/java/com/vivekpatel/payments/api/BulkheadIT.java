package com.vivekpatel.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vivekpatel.payments.processor.FakeProcessor;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The bulkhead with the production limit of 20: what the payment path may hold, and that the
 * rest is refused fast rather than queued.
 *
 * <p>MockMvc has no Tomcat thread pool, so this proves the cap and the refusal, not the threads
 * they protect. The thread-exhaustion half - reads staying fast while payments flood a slow
 * processor - needs a real server, and was measured on the Docker stack (ADR-008).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BulkheadIT {

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
    BulkheadRegistry bulkheads;

    @AfterEach
    void reset() {
        processor.approveAll();
    }

    @AfterAll
    static void stopProcessor() {
        processor.close();
    }

    @Test
    void aSlowProcessorNeverHoldsMoreThanTwentyPaymentsAndTheRestAreRefusedFast() throws Exception {
        assertThat(bulkheads.bulkhead("processor").getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
        String existing = createPayment();
        // Slow but successful - under the test profile's 1s read timeout, so nothing times out and
        // the circuit breaker stays out of it. The case nothing alerts on.
        processor.approveAfter(Duration.ofMillis(700));

        int callers = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Integer> statuses = new ArrayList<>();
        long refusedSlowest = 0;
        try {
            List<Future<long[]>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    long began = System.nanoTime();
                    MvcResult r = mockMvc.perform(post("/payments")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body("merchant-" + UUID.randomUUID())))
                            .andReturn();
                    return new long[] {r.getResponse().getStatus(), System.nanoTime() - began};
                }));
            }
            start.countDown();

            // While those are in flight, a read: it never waits on the payment path.
            long readBegan = System.nanoTime();
            mockMvc.perform(get("/payments/{id}", existing)).andExpect(status().isOk());
            assertThat(Duration.ofNanos(System.nanoTime() - readBegan)).isLessThan(Duration.ofMillis(500));

            for (Future<long[]> call : calls) {
                long[] result = call.get(30, TimeUnit.SECONDS);
                statuses.add((int) result[0]);
                if (result[0] == 503) {
                    refusedSlowest = Math.max(refusedSlowest, result[1]);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(processor.mostInFlight()).as("most calls the processor saw at once").isLessThanOrEqualTo(20);
        assertThat(statuses).containsOnly(201, 503);
        assertThat(statuses.stream().filter(s -> s == 503).count()).as("refused").isPositive();
        // Refused without waiting for a permit: well under the 700ms a permitted call takes.
        assertThat(Duration.ofNanos(refusedSlowest)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void thePermitsComeBackSoTheNextPaymentGoesThrough() throws Exception {
        processor.approveAfter(Duration.ofMillis(200));
        for (int i = 0; i < 30; i++) {
            createPayment();
        }
        assertThat(bulkheads.bulkhead("processor").getMetrics().getAvailableConcurrentCalls()).isEqualTo(20);
    }

    private String createPayment() throws Exception {
        String body = mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("merchant-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String body(String merchant) {
        return """
                {"merchantId":"%s","amount":12500,"currency":"INR","paymentMethodToken":"tok_test_123"}"""
                .formatted(merchant);
    }
}
