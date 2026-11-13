-- =============================================================================================
-- V3 - one payment_attempts row per processor request id, not per call.
--
-- Until now every call to the processor got its own row. But a retry sends the SAME request id, so
-- the processor treats it as the same authorisation: at most one charge, however many calls it
-- took. A row per call made one authorisation look like several, and a duplicated answer (the
-- processor delivering one result twice) look like two attempts. Now a retry updates the row for
-- its request id and counts the call, and the database refuses a second row for the same id.
--
-- What this gives up: the outcome of each failed call before the last. The row keeps the final
-- outcome and how many calls it took; the per-call detail is in the logs.
-- =============================================================================================

ALTER TABLE payment_attempts ADD COLUMN processor_request_id VARCHAR(128);
ALTER TABLE payment_attempts ADD COLUMN call_count INT NOT NULL DEFAULT 1;

-- Rows written before V3 were one per call, several of them sharing a request id. Give each a
-- unique placeholder instead of pretending to know which call they belonged to.
UPDATE payment_attempts SET processor_request_id = 'pre-v3:' || id WHERE processor_request_id IS NULL;

ALTER TABLE payment_attempts ALTER COLUMN processor_request_id SET NOT NULL;
ALTER TABLE payment_attempts
    ADD CONSTRAINT payment_attempts_one_row_per_request
        UNIQUE (payment_id, operation, processor_request_id);
ALTER TABLE payment_attempts
    ADD CONSTRAINT payment_attempts_call_count_positive CHECK (call_count >= 1);

COMMENT ON TABLE payment_attempts IS
    'One row per request id sent to the processor - the reason "did we already charge this card?" is '
    'answerable after a timeout. Retries reuse the request id, so they update this row and count the '
    'call rather than adding a row: one authorisation, one row, however many calls it took. When the '
    'processor times out, payments.status still says PROCESSING and the money may or may not have '
    'moved; this row records what was asked, and the last thing that came back, or that nothing did.';

COMMENT ON COLUMN payment_attempts.processor_request_id IS
    'The idempotency key sent to the processor. Unique per payment and operation, so a duplicated '
    'answer for the same request cannot become a second attempt. Rows from before V3 carry pre-v3:<id>.';

COMMENT ON COLUMN payment_attempts.call_count IS
    'How many calls this request id took, retries included. outcome, error_code and latency_ms '
    'describe the last call.';
