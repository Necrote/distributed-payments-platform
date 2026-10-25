package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vivekpatel.payments.domain.Money;
import com.vivekpatel.payments.domain.Payment;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Transaction boundaries of {@link PaymentApplicationService} and {@link IdempotentPaymentCreator},
 * against real PostgreSQL. Called directly rather than over HTTP, so a test can hold a transaction
 * open, run requests on several threads, or hand in input the controller would have rejected.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentApplicationServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Money AMOUNT = Money.of(12500, "INR");

    @Autowired
    PaymentApplicationService service;

    @Autowired
    IdempotentPaymentCreator creator;

    @Autowired
    TransactionTemplate tx;

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
                        "merchant-rollback", AMOUNT, tooLongToken, null, key))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Long.class, key))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payments WHERE merchant_id = 'merchant-rollback'", Long.class))
                .isZero();
    }

    @Test
    void retryWhileTheFirstIsInFlightWaitsThenReplaysItsPayment() throws Exception {
        String merchant = "merchant-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        ExecutorService retryThread = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CreatePaymentResult> retry = new CompletableFuture<>();
            Payment first = tx.execute(status -> {
                // The first request: key row flushed, transaction NOT committed.
                Payment created = service.create(merchant, AMOUNT, "tok", null, key);
                retryThread.submit(() -> complete(retry,
                        () -> creator.create(merchant, AMOUNT, "tok", null, key)));
                // Prove the retry is parked on the unique index, not merely slow.
                awaitABackendBlockedOnALock();
                assertThat(retry).isNotDone();
                return created;
            });
            // First commits -> the retry's INSERT fails -> fresh read -> replay.

            CreatePaymentResult result = retry.get(10, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(CreatePaymentResult.Replayed.class);
            assertThat(((CreatePaymentResult.Replayed) result).response().id()).isEqualTo(first.getId());
            assertThat(paymentCount(merchant)).isOne();
        } finally {
            retryThread.shutdownNow();
        }
    }

    @Test
    void retryWhileTheFirstIsInFlightCreatesThePaymentIfTheFirstRollsBack() throws Exception {
        String merchant = "merchant-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        ExecutorService retryThread = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CreatePaymentResult> retry = new CompletableFuture<>();
            tx.executeWithoutResult(status -> {
                service.create(merchant, AMOUNT, "tok", null, key);
                retryThread.submit(() -> complete(retry,
                        () -> creator.create(merchant, AMOUNT, "tok", null, key)));
                awaitABackendBlockedOnALock();
                status.setRollbackOnly();
            });
            // First rolls back -> its key row never existed -> the retry's INSERT goes through.

            assertThat(retry.get(10, TimeUnit.SECONDS)).isInstanceOf(CreatePaymentResult.Created.class);
            assertThat(paymentCount(merchant)).isOne();
        } finally {
            retryThread.shutdownNow();
        }
    }

    @Test
    void manySimultaneousRequestsWithOneKeyCreateExactlyOnePayment() throws Exception {
        // Below the pool size of 10, so every thread can hold a connection at the same time.
        int threads = 8;
        String merchant = "merchant-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<CreatePaymentResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                CompletableFuture<CreatePaymentResult> result = new CompletableFuture<>();
                results.add(result);
                pool.submit(() -> {
                    start.await();
                    complete(result, () -> creator.create(merchant, AMOUNT, "tok", null, key));
                    return null;
                });
            }
            start.countDown();

            List<UUID> ids = new ArrayList<>();
            int created = 0;
            for (CompletableFuture<CreatePaymentResult> f : results) {
                switch (f.get(30, TimeUnit.SECONDS)) {
                    case CreatePaymentResult.Created c -> {
                        created++;
                        ids.add(c.payment().getId());
                    }
                    case CreatePaymentResult.Replayed r -> ids.add(r.response().id());
                }
            }
            assertThat(created).isOne();
            assertThat(ids).hasSize(threads).containsOnly(ids.get(0));
            assertThat(paymentCount(merchant)).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void creatorRefusesToRunInsideAnExistingTransaction() {
        // Inside an outer transaction the duplicate-key violation would poison the very
        // transaction the recovery read needs. NEVER makes that a loud mistake.
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                        creator.create("m-never", AMOUNT, "tok", null, "key-" + UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * The version check itself, made deterministic. A start latch only lines threads up at the
     * beginning; whether both then read before either commits is luck. Here each capture runs
     * inside a transaction that waits at a barrier AFTER the read and the in-memory transition, and
     * BEFORE commit - so both transactions are guaranteed to hold a stale AUTHORIZED snapshot.
     * Only the version column can stop the second write now.
     *
     * <p>What happens at commit: the first UPDATE ... WHERE version = 1 succeeds. The second blocks
     * on the row lock until the first commits; PostgreSQL then re-checks its WHERE against the new
     * row (version = 2), matches zero rows, and Hibernate turns "0 rows updated" into the
     * exception.
     */
    @RepeatedTest(20)
    void staleCaptureLosesOnTheVersionColumn() throws Exception {
        UUID id = service.create("merchant-race", AMOUNT, "tok", null, null).getId();
        service.forceAuthorize(id);

        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Payment>> captures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                CompletableFuture<Payment> capture = new CompletableFuture<>();
                captures.add(capture);
                pool.submit(() -> complete(capture, () -> tx.execute(status -> {
                    // Joins the template's transaction: the UPDATE is deferred to its commit.
                    Payment captured = service.capture(id);
                    awaitQuietly(bothHaveRead);
                    return captured;
                })));
            }

            int won = 0;
            List<Throwable> lost = new ArrayList<>();
            for (CompletableFuture<Payment> capture : captures) {
                try {
                    capture.get(10, TimeUnit.SECONDS);
                    won++;
                } catch (ExecutionException e) {
                    lost.add(e.getCause());
                }
            }
            assertThat(won).isOne();
            assertThat(lost).singleElement().isInstanceOf(OptimisticLockingFailureException.class);
        } finally {
            pool.shutdownNow();
        }

        // create = 0, force-authorize = 1, the one capture that committed = 2.
        assertThat(jdbc.queryForMap("SELECT status, version FROM payments WHERE id = ?", id))
                .containsEntry("status", "CAPTURED")
                .containsEntry("version", 2L);
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            // A timeout rather than forever: if the other thread died before the barrier, fail
            // the test instead of hanging the build.
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The other capture never reached the barrier", e);
        }
    }

    private long paymentCount(String merchant) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchant);
    }

    /** Polls pg_stat_activity until some backend is waiting on a lock - our blocked INSERT. */
    private void awaitABackendBlockedOnALock() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Long waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("No backend ever blocked on the unique index");
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private static <T> void complete(CompletableFuture<T> future, Call<T> call) {
        try {
            future.complete(call.run());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
