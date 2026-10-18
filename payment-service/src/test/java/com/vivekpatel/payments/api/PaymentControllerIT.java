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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PaymentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(12500))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.externalReference").value("order-827361"))
                // The DTO, not the entity: neither the lock column nor the card token leaks.
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.paymentMethodToken").doesNotExist());
    }

    @Test
    void captureOfACreatedPaymentIs409AndChangesNothing() throws Exception {
        UUID id = createPayment();

        mockMvc.perform(post("/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Illegal payment state transition"))
                .andExpect(jsonPath("$.currentStatus").value("CREATED"));

        // The rejection happened before any write: still CREATED, version untouched.
        Payment row = payments.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(row.getVersion()).isZero();
    }

    @Test
    void authorizedPaymentCanBeCapturedOnceThenRefunded() throws Exception {
        UUID id = createPayment();
        mockMvc.perform(post("/admin/payments/{id}/force-authorize", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

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
        mockMvc.perform(post("/admin/payments/{id}/force-authorize", id)).andExpect(status().isOk());

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
        mockMvc.perform(post("/admin/payments/{id}/force-authorize", id)).andExpect(status().isOk());

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

        // Exactly one write: create = 0, force-authorize (one transaction, one UPDATE) = 1,
        // one capture = 2. A double capture would be 3, or a lost update would hide at 2 with
        // two 200s - which the status assertion above already rules out.
        Payment row = payments.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(row.getVersion()).isEqualTo(2L);
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
        mockMvc.perform(post("/admin/payments/{id}/force-authorize", id)).andExpect(status().isOk());

        // The retry asks "what happened to my create?" - and the create returned CREATED.
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CREATED"));
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
