package com.vivekpatel.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vivekpatel.payments.domain.Payment;
import com.vivekpatel.payments.domain.PaymentStatus;
import com.vivekpatel.payments.persistence.PaymentRepository;
import com.vivekpatel.payments.processor.FakeProcessor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests: real Spring context, real PostgreSQL in a container, real Flyway migrations.
 * Nothing is mocked below the controller.
 *
 * <p>{@code @ServiceConnection} (Spring Boot 3.1+) wires the container's JDBC URL, username and
 * password into the context automatically - no {@code @DynamicPropertySource} block needed. That
 * one annotation is a concrete, demonstrable example of "what changed between Spring Boot 2.3 and
 * Spring Boot 3", which is a question you will be asked.
 *
 * <p>The container is {@code static}, so it starts once for the whole class instead of once per
 * test. On a slow laptop that difference is minutes.
 *
 * <p><b>Naming matters for the build.</b> Classes ending in {@code IT} are run by failsafe during
 * {@code mvn verify}, not by surefire during {@code mvn test} - so a developer without Docker
 * running can still get a fast unit-test loop, while CI runs everything.
 *
 * <p>Tests share one database and do not clean up. Each creates its own payment with a fresh id,
 * so none depends on what the others left behind.
 *
 * <p>The processor is a {@link FakeProcessor} on a real local socket, so the whole path - request
 * factory, timeouts, status mapping - runs as in production. It approves unless a test says
 * otherwise, and goes back to approving after every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PaymentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static FakeProcessor processor = FakeProcessor.start();

    @DynamicPropertySource
    static void processorUrl(DynamicPropertyRegistry registry) {
        registry.add("payments.processor.base-url", processor::baseUrl);
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @AfterEach
    void processorBackToApproving() {
        processor.approveAll();
        // The breaker lives as long as the Spring context, which the tests share. Several tests
        // here fail calls on purpose, and they must not add up to an open breaker for the next.
        circuitBreakers.circuitBreaker("processor").reset();
    }

    @AfterAll
    static void stopProcessor() {
        processor.close();
    }

    private static final String VALID_BODY =
            """
            {
              "merchantId": "merchant-123",
              "amount": 12500,
              "currency": "INR",
              "paymentMethodToken": "tok_test_123",
              "externalReference": "order-827361"
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentRepository payments;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createThenFetchReturnsTheSamePayment() throws Exception {
        MvcResult created =
                mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String location = created.getResponse().getHeader("Location");
        assertThat(location).endsWith("/payments/" + id);

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amount").value(12500))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.externalReference").value("order-827361"))
                // The DTO, not the entity: neither the lock column nor the card token leaks.
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.paymentMethodToken").doesNotExist());
    }

    /**
     * Today's "done": a create against an approving processor produces a payment, an attempt row,
     * and AUTHORIZED - all from one POST.
     */
    @Test
    void createAuthorizesWithTheProcessorAndRecordsTheAttempt() throws Exception {
        UUID id = createPayment();

        Map<String, Object> payment =
                jdbc.queryForMap("SELECT status, version FROM payments WHERE id = ?", id);
        assertThat(payment).containsEntry("status", "AUTHORIZED");
        // create = 0, -> PROCESSING = 1, -> AUTHORIZED = 2: two committed steps, not one.
        assertThat(payment).containsEntry("version", 2L);

        Map<String, Object> attempt = jdbc.queryForMap(
                """
                SELECT attempt_number, operation, outcome, processor_reference, error_code, latency_ms
                FROM payment_attempts WHERE payment_id = ?
                """,
                id);
        assertThat(attempt)
                .containsEntry("attempt_number", 1)
                .containsEntry("operation", "AUTHORIZE")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("error_code", null);
        assertThat((String) attempt.get("processor_reference")).startsWith("auth_");
        assertThat((Integer) attempt.get("latency_ms")).isNotNull().isNotNegative();

        // What the processor was sent: our stable request id, and minor units.
        assertThat(processor.requestBodies().getLast())
                .contains("\"requestId\":\"authorize:" + id + "\"")
                .contains("\"amount\":12500");
    }

    /**
     * The pitfall, end to end: a decline is a business outcome. The request succeeded (201), the
     * payment is FAILED, and the attempt says why. Nothing about it is a server error.
     */
    @Test
    void declinedPaymentIsCreatedAsFailedWithTheDeclineCodeRecorded() throws Exception {
        processor.declineAll("INSUFFICIENT_FUNDS");

        String body = mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(jdbc.queryForMap(
                        "SELECT outcome, error_code, processor_reference FROM payment_attempts WHERE payment_id = ?",
                        id))
                .containsEntry("outcome", "FAILURE")
                .containsEntry("error_code", "INSUFFICIENT_FUNDS")
                .containsEntry("processor_reference", null);
    }

    /**
     * Today's "done": a bounded error at our own deadline, one attempt recorded as TIMEOUT, not
     * FAILURE. A timeout is not a decline, so the payment stays PROCESSING - the processor may have
     * charged the card after we stopped listening. And no retry: the timeout used up the budget.
     */
    @Test
    void processorTimeoutIsABounded504AndTheAttemptIsTimeoutNotFailure() throws Exception {
        // Past the test profile's 1 s read timeout.
        processor.approveAfter(Duration.ofSeconds(3));
        int callsBefore = processor.requestBodies().size();

        long started = System.nanoTime();
        String body = mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Payment outcome unknown"))
                .andExpect(jsonPath("$.detail").value(containsString("Do not create a new payment")))
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        // We gave up at our deadline (~1 s), not when the processor finished (3 s).
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
        UUID id = UUID.fromString(JsonPath.read(body, "$.paymentId"));

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
                .isEqualTo("PROCESSING");
        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT outcome, error_code, latency_ms FROM payment_attempts WHERE payment_id = ?", id);
        assertThat(attempt)
                .containsEntry("outcome", "TIMEOUT")
                .containsEntry("error_code", "READ_TIMEOUT");
        assertThat((Integer) attempt.get("latency_ms")).isBetween(900, 2500);
        assertThat(processor.requestBodies()).hasSize(callsBefore + 1);
    }

    @Test
    void retryWithTheSameKeyAfterATimeoutGetsThe504AgainAndNeverCallsTheProcessor() throws Exception {
        String key = "key-" + UUID.randomUUID();
        processor.approveAfter(Duration.ofSeconds(3));
        String first = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isGatewayTimeout())
                .andReturn().getResponse().getContentAsString();
        processor.approveAll();
        int callsBefore = processor.requestBodies().size();

        String replay = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isGatewayTimeout())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(processor.requestBodies()).hasSize(callsBefore);
    }

    /**
     * The retry, for real: each call its own attempt row, and every call the same request id, which
     * is what makes retrying after a broken connection safe at a processor that deduplicates.
     */
    @Test
    void brokenConnectionsAreRetriedWithTheSameRequestIdAndARowPerCall() throws Exception {
        processor.breakNextConnections(2);
        int callsBefore = processor.requestBodies().size();

        String body = mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(jdbc.queryForList(
                        "SELECT outcome FROM payment_attempts WHERE payment_id = ? ORDER BY attempt_number",
                        String.class, id))
                .containsExactly("UNKNOWN", "UNKNOWN", "SUCCESS");
        List<String> calls = processor.requestBodies();
        assertThat(calls.subList(callsBefore, calls.size()))
                .hasSize(3)
                .allSatisfy(call -> assertThat(call).contains("\"requestId\":\"authorize:" + id + "\""));
    }

    @Test
    void aDeclineIsNeverRetried() throws Exception {
        processor.declineAll("INSUFFICIENT_FUNDS");
        int callsBefore = processor.requestBodies().size();

        createPayment();

        assertThat(processor.requestBodies()).hasSize(callsBefore + 1);
    }

    @Test
    void captureOfADeclinedPaymentIs409AndChangesNothing() throws Exception {
        processor.declineAll("DO_NOT_HONOR");
        UUID id = createPayment();

        mockMvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Illegal payment state transition"))
                .andExpect(jsonPath("$.currentStatus").value("FAILED"));

        // The rejection happened before any write: still FAILED, version where authorisation left it.
        Payment row = payments.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(row.getVersion()).isEqualTo(2L);
    }

    @Test
    void authorizedPaymentCanBeCapturedOnceThenRefunded() throws Exception {
        UUID id = createPayment();

        mockMvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        // CAPTURED -> CAPTURED is not a transition: a sequential second capture is rejected.
        mockMvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CAPTURED"));

        mockMvc.perform(post("/payments/{id}/refund", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"));

        assertThat(payments.findById(id).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    @Test
    void refundOfAnAuthorizedPaymentIs409() throws Exception {
        // Nothing was captured, so there is nothing to give back: that is a void, not a refund.
        UUID id = createPayment();

        mockMvc.perform(post("/payments/{id}/refund", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("AUTHORIZED"));
    }

    /**
     * Two simultaneous captures of one AUTHORIZED payment: exactly one 200, one 409, one capture.
     *
     * <p>The latch releases both threads at the same instant; nothing here sleeps to "line them
     * up". What the latch cannot control is the interleaving after that, so the loser's 409 has
     * two legitimate shapes: a version conflict (both read AUTHORIZED, the second commit lost) or
     * an illegal transition (it read CAPTURED because the first had already committed). Both are
     * accepted; the assertions are on what must always hold. The version conflict itself is
     * forced deterministically in {@code PaymentApplicationServiceIT}.
     *
     * <p>Repeated 20 times: a concurrency test that passes once proves little.
     */
    @RepeatedTest(20)
    void twoSimultaneousCapturesProduceExactlyOneCapture() throws Exception {
        UUID id = createPayment();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/payments/{id}/capture", id)).andReturn();
                }));
            }
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> call : calls) {
                results.add(call.get(10, TimeUnit.SECONDS));
            }
            assertThat(results)
                    .extracting(r -> r.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult loser = results.stream()
                    .filter(r -> r.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat((String) JsonPath.read(loser.getResponse().getContentAsString(), "$.title"))
                    .isIn("Concurrent update", "Illegal payment state transition");
        } finally {
            pool.shutdownNow();
        }

        // Exactly one capture write: create = 0, -> PROCESSING = 1, -> AUTHORIZED = 2, one
        // capture = 3. A double capture would be 4, or a lost update would hide at 3 with two
        // 200s - which the status assertion above already rules out.
        Payment row = payments.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(row.getVersion()).isEqualTo(3L);
    }

    @Test
    void createWithAnIdempotencyKeyWritesTheKeyRowAndThePayment() throws Exception {
        String key = "key-" + UUID.randomUUID();
        MvcResult created =
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                        .andExpect(status().isCreated())
                        .andReturn();
        String responseBody = created.getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(responseBody, "$.id"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE id = ?", Long.class, id))
                .isOne();
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT payment_id, response_status, request_hash,
                       jsonb_typeof(response_body) AS body_type,
                       response_body ->> 'id' AS body_id,
                       extract(epoch FROM expires_at - created_at)::bigint AS ttl_seconds
                FROM idempotency_keys WHERE merchant_id = 'merchant-123' AND idempotency_key = ?
                """,
                key);
        assertThat(row.get("payment_id")).isEqualTo(id);
        assertThat(row.get("response_status")).isEqualTo(201);
        assertThat((String) row.get("request_hash")).matches("[0-9a-f]{64}");
        // An object, not a JSON string containing an object: the body was not double-encoded.
        assertThat(row.get("body_type")).isEqualTo("object");
        assertThat(row.get("body_id")).isEqualTo(id.toString());
        assertThat(row.get("ttl_seconds")).isEqualTo(24L * 60 * 60);

        String storedBody = jdbc.queryForObject(
                "SELECT response_body::text FROM idempotency_keys WHERE idempotency_key = ?",
                String.class, key);
        JSONAssert.assertEquals(responseBody, storedBody, JSONCompareMode.STRICT);
    }

    @Test
    void createWithoutAnIdempotencyKeyWritesNoKeyRow() throws Exception {
        UUID id = createPayment();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE payment_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void sameIdempotencyKeyTwiceCreatesOnePayment() throws Exception {
        // A merchant of its own, so "one row in payments" counts only this test's writes.
        String merchant = "merchant-" + UUID.randomUUID();
        String body = VALID_BODY.replace("merchant-123", merchant);
        String key = "key-" + UUID.randomUUID();

        MvcResult first =
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(header().doesNotExist("Idempotent-Replayed"))
                        .andReturn();
        MvcResult second =
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Idempotent-Replayed", "true"))
                        .andReturn();

        // Byte-identical, not just the same id: the replay is the first response, not a fresh
        // rendering of the payment's current state.
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE merchant_id = ?", Long.class, merchant))
                .isOne();
    }

    /**
     * The guarantee under real concurrency: 20 identical requests, one key, released at once.
     *
     * <p>20 is twice the connection pool (10), on purpose. The winner and up to nine losers hold a
     * connection while they wait on the unique index; the rest wait for a connection. That only
     * works because the winner's transaction is short - it commits, the blocked inserts fail fast
     * and hand their connections back. If something slow ever moves inside that transaction, this
     * is the test that times out first.
     */
    @Test
    void twentySimultaneousIdenticalRequestsCreateOnePaymentAndAllCarryItsId() throws Exception {
        int threads = 20;
        String merchant = "merchant-" + UUID.randomUUID();
        String body = VALID_BODY.replace("merchant-123", merchant);
        String key = "key-" + UUID.randomUUID();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<MvcResult> results = new ArrayList<>();
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/payments")
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                }));
            }
            start.countDown();
            for (Future<MvcResult> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results)
                .extracting(r -> r.getResponse().getStatus())
                .as("one creator, nineteen replays")
                .containsOnly(201, 200)
                .filteredOn(s -> s == 201).hasSize(1);
        List<String> ids = new ArrayList<>();
        for (MvcResult r : results) {
            ids.add(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
        }
        assertThat(ids).hasSize(threads).containsOnly(ids.get(0));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant))
                .isOne();
    }

    @Test
    void replayReturnsTheOriginalResponseEvenAfterThePaymentHasMoved() throws Exception {
        String key = "key-" + UUID.randomUUID();
        MvcResult first =
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                        .andExpect(status().isCreated())
                        .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/payments/{id}/capture", id)).andExpect(status().isOk());
        int processorCalls = processor.requestBodies().size();

        // The retry asks "what happened to my create?" - and the create returned AUTHORIZED: the
        // payment after authorisation, not the CREATED snapshot, and not today's CAPTURED.
        MvcResult replay = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // And a replay never calls the processor: one authorisation per payment.
        assertThat(processor.requestBodies()).hasSize(processorCalls);
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() throws Exception {
        // Silently returning the first payment here would be the single most dangerous bug this
        // service could ship: the client would record 999 as charged; we charged 12500.
        String merchant = "merchant-" + UUID.randomUUID();
        String original = VALID_BODY.replace("merchant-123", merchant);
        String key = "key-" + UUID.randomUUID();
        String firstBody =
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(original))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(firstBody, "$.id");

        String problem =
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(original.replace("12500", "999")))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.title").value("Idempotency-Key reused with a different request"))
                        .andExpect(jsonPath("$.detail").value(containsString("already used for payment " + id)))
                        .andExpect(jsonPath("$.detail").value(containsString("Differing fields: amount.")))
                        .andExpect(jsonPath("$.originalPaymentId").value(id))
                        .andExpect(jsonPath("$.differingFields", contains("amount")))
                        .andReturn().getResponse().getContentAsString();
        // Field names, never values: neither amount appears in the error. The random ids are
        // removed first, or a UUID that happens to contain "999" would fail this by chance.
        assertThat(problem.replace(id, "").replace(key, ""))
                .doesNotContain("999")
                .doesNotContain("12500");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT amount_minor FROM payments WHERE merchant_id = ?", Long.class, merchant))
                .isEqualTo(12500L);
    }

    @Test
    void sameRequestWithFieldsReorderedAndRespacedIsAReplayNotA422() throws Exception {
        // The pitfall: hash instability. A client library that serialises its map in a different
        // order on the retry has sent the same request, and must get the replay.
        String key = "key-" + UUID.randomUUID();
        String reordered =
                """
                {"externalReference":"order-827361",   "paymentMethodToken":"tok_test_123",
                    "currency":"INR","amount":12500,
                 "merchantId":"merchant-123"}
                """;
        String id = JsonPath.read(
                mockMvc.perform(post("/payments")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id");

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reordered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    private UUID createPayment() throws Exception {
        String body =
                mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
