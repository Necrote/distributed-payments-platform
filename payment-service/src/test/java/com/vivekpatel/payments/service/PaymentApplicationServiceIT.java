package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vivekpatel.payments.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Transaction boundaries of {@link PaymentApplicationService}, against real PostgreSQL. Called
 * directly rather than over HTTP, so a test can hand it input the controller's validation would
 * have rejected.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentApplicationServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    PaymentApplicationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void keyRowIsRolledBackWhenThePaymentInsertFails() {
        // The key row is flushed - sent to PostgreSQL - before the payment exists. The payment
        // then fails at the database (payment_method_token is VARCHAR(128)). If the key were
        // committed on its own, it would survive here pointing at nothing; one transaction means
        // it goes too.
        String key = "key-" + UUID.randomUUID();
        String tooLongToken = "t".repeat(129);

        assertThatThrownBy(() -> service.create(
                        "merchant-rollback", Money.of(12500, "INR"), tooLongToken, null, key))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Long.class, key))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments WHERE merchant_id = 'merchant-rollback'", Long.class))
                .isZero();
    }
}
