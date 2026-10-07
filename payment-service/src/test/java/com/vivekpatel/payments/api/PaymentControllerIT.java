package com.vivekpatel.payments.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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

    @Test
    @Disabled("TODO Phase 2 (Day 8)")
    void sameIdempotencyKeyTwiceCreatesOnePayment() throws Exception {
        // TODO Phase 2 (Day 8): POST the identical body twice with the same Idempotency-Key.
        //   Expect 201 then 200, the SAME payment id, and exactly one row in `payments`.
        //   This is the test that proves the flagship feature - write it before the implementation.
    }

    @Test
    @Disabled("TODO Phase 2 (Day 9)")
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() throws Exception {
        // TODO Phase 2 (Day 9): same key, amount changed from 12500 to 999 -> expect 422 and a
        //   ProblemDetail explaining the conflict. Silently returning the first payment here would
        //   be the single most dangerous bug this service could ship.
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
