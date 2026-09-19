package com.vivekpatel.payments.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test skeleton: real Spring context, real PostgreSQL in a container, real Flyway
 * migrations. Nothing is mocked below the controller.
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Disabled("TODO Phase 1 (Day 5): enable once POST /payments and GET /payments/{id} are implemented")
class PaymentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Test
    void createThenFetchReturnsTheSamePayment() throws Exception {
        // TODO Phase 1 (Day 5):
        //   1. POST /payments with a valid body -> expect 201 and a Location header.
        //   2. GET the Location -> expect 200, status CREATED, amount 12500, currency INR.
        //   3. Assert the response body is the DTO, not the entity (no "version" field leaking).
        assertTrue(postgres.isRunning());
    }

    @Test
    void sameIdempotencyKeyTwiceCreatesOnePayment() throws Exception {
        // TODO Phase 2 (Day 8): POST the identical body twice with the same Idempotency-Key.
        //   Expect 201 then 200, the SAME payment id, and exactly one row in `payments`.
        //   This is the test that proves the flagship feature - write it before the implementation.
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() throws Exception {
        // TODO Phase 2 (Day 9): same key, amount changed from 12500 to 999 -> expect 422 and a
        //   ProblemDetail explaining the conflict. Silently returning the first payment here would
        //   be the single most dangerous bug this service could ship.
    }
}
