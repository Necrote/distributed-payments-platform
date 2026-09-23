-- =============================================================================================
-- V2 - move the reasoning from V1 into the database itself.
--
-- V1 explains every table in SQL `--` comments, which PostgreSQL discards at parse time: they
-- live in git and nowhere else. COMMENT ON stores text in pg_description, so `\d+ payments` and
-- `\dt+` show it to whoever is in psql at 3 a.m. with no checkout and no context.
--
-- This migration adds no columns, no constraints and no indexes. It is metadata only, it takes no
-- meaningful locks, and it is safe to run against a live database.
--
-- Keep these SHORT. A comment is a signpost, not the design document - the full argument stays in
-- V1 and in docs/. If a comment here ever contradicts V1, V1 is wrong too and both need fixing.
--
-- Note on quoting: these are SQL string literals, so a literal apostrophe must be doubled ('').
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- Tables owned by payment-service.
-- ---------------------------------------------------------------------------------------------

COMMENT ON TABLE payments IS
    'The aggregate root: one row per payment, mutated only through the state machine in Java. '
    'Money is (amount_minor, currency) in MINOR UNITS - 12500 + INR is Rs. 125.00, never a decimal. '
    'Do not UPDATE status by hand; the legal transitions live in PaymentStatus and the check '
    'constraint here is the last line of defence, not the first.';

COMMENT ON COLUMN payments.external_reference IS
    'The merchant''s own reference (an order number, typically). Deliberately not a key: callers do '
    'not choose our identifiers, and two merchants may reuse the same reference.';

COMMENT ON COLUMN payments.version IS
    'Optimistic lock. Hibernate appends "AND version = ?" to every UPDATE, so of two concurrent '
    'captures the second updates zero rows and fails instead of double-recording.';

COMMENT ON TABLE payment_attempts IS
    'One row per call to the external processor - the reason "did we already charge this card?" is '
    'answerable after a timeout. When the processor times out, payments.status still says PROCESSING '
    'and the money may or may not have moved; this table records what was sent, when, and what came '
    'back, or that nothing did.';

COMMENT ON COLUMN payment_attempts.outcome IS
    'PENDING | SUCCESS | FAILURE | TIMEOUT | UNKNOWN. UNKNOWN is not laziness: a timed-out call whose '
    'result was never confirmed genuinely is unknown, and pretending otherwise causes double charges.';

COMMENT ON TABLE refunds IS
    'A refund is its own entity, not a flag on the payment: it has its own lifecycle, its own '
    'idempotency key and its own failure mode. Modelling it as payments.refunded = true would make '
    '"the refund failed, retry it" unrepresentable. Full refunds only - partials are a documented '
    'limitation, not an oversight.';

COMMENT ON TABLE idempotency_keys IS
    'The contract behind POST /payments: the same Idempotency-Key with the same body returns the '
    'original payment and never charges twice, while the same key with a DIFFERENT body is a 422 and '
    'never a silent replay. UNIQUE (merchant_id, idempotency_key) is what makes "exactly one payment" '
    'a fact rather than a hope - a cache can miss under concurrency, this constraint cannot. Insert '
    'the row in the SAME transaction as the payment and treat the duplicate-key violation as the '
    'happy path. Rows expire (24h) and are swept; unbounded growth on the hottest write table is a '
    'real outage.';

COMMENT ON COLUMN idempotency_keys.request_hash IS
    'SHA-256 hex of the canonical request body. Same key + different hash means the client has a bug; '
    'surfacing it as 422 is the difference between a loud error and charging the wrong amount.';

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. The event row is written in the SAME transaction as the state change, so '
    'the two commit atomically - "UPDATE payment; COMMIT; kafka.send()" can lose the publish and '
    'leave the database claiming AUTHORIZED while nothing downstream ever hears. A separate publisher '
    'polls published_at IS NULL (FOR UPDATE SKIP LOCKED) and marks rows sent. Publish-then-mark is '
    'NOT atomic, so delivery is AT-LEAST-ONCE and consumers must be idempotent. This is not '
    'exactly-once; do not call it that.';

COMMENT ON COLUMN outbox_events.aggregate_id IS
    'Becomes the Kafka partition key, so all events for one payment share a partition and stay '
    'ordered relative to each other. Ordering ACROSS payments is not guaranteed - depend on it and '
    'you have built a bug that only appears under load.';

-- ---------------------------------------------------------------------------------------------
-- Tables owned by ledger-service (still physically here until the Phase 4 schema split).
-- ---------------------------------------------------------------------------------------------

COMMENT ON TABLE processed_events IS
    'How a consumer stays idempotent. The event id is recorded in the SAME transaction that writes '
    'the ledger entries, so a redelivered event hits this primary key, rolls back, and posts nothing. '
    'At-least-once transport plus this table equals exactly-once BUSINESS EFFECTS, which is the only '
    'kind of exactly-once anyone can honestly claim. Owned by ledger-service.';

COMMENT ON TABLE ledger_entries IS
    'Double-entry and append-only. Every money movement is at least two rows - one DEBIT, one CREDIT, '
    'equal magnitude, same transaction_ref - and the invariant is that debits equal credits per ref '
    'and globally. Corrections are new reversing entries: never UPDATE, never DELETE. An audit trail '
    'you can edit is not an audit trail. Owned by ledger-service.';

COMMENT ON COLUMN ledger_entries.direction IS
    'DEBIT | CREDIT. Amounts are always positive and the sign lives here - two ways to express one '
    'movement is one way too many in a ledger.';
