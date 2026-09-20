package com.vivekpatel.ledger.domain;

/**
 * The design of the ledger, written down before any of it is built.
 *
 * <p><b>Read this before Day 25.</b> Everything below is decided; the implementation is mechanical
 * once the rules are clear, and the rules are the part an interviewer will probe.
 *
 * <h2>1. Double entry, in one sentence</h2>
 *
 * Every movement of money is recorded twice: once as a debit and once as a credit, of equal
 * magnitude, in the same database transaction. Nothing is ever recorded once.
 *
 * <pre>
 * Capture of Rs. 125.00 (12500 minor units, INR):
 *     DEBIT   CUSTOMER_RECEIVABLE   12500 INR
 *     CREDIT  MERCHANT_PAYABLE      12500 INR
 *
 * Refund of the same payment - the mirror image, never an edit of the rows above:
 *     DEBIT   MERCHANT_PAYABLE      12500 INR
 *     CREDIT  CUSTOMER_RECEIVABLE   12500 INR
 * </pre>
 *
 * <h2>2. The invariant</h2>
 *
 * <pre>
 * SUM(debits) = SUM(credits)
 * </pre>
 *
 * per {@code transaction_ref}, and globally across the whole table. It holds after every committed
 * transaction, and it is asserted directly in the tests - not approximated by "the totals looked
 * right". If it ever fails, the ledger is wrong and everything built on top of it is wrong too, so
 * this is the one assertion that is allowed to be paranoid.
 *
 * <h2>3. Append-only</h2>
 *
 * There is no UPDATE and no DELETE on {@code ledger_entries}. A mistake is corrected by posting a
 * reversing entry, which leaves both the error and the correction visible - that is what makes the
 * table an audit trail rather than a report. Enforce it with a database GRANT (the application role
 * gets INSERT and SELECT only), not with a comment: a comment is a wish, a GRANT is a rule.
 *
 * <p>The practical consequence: a balance is a query ({@code SUM} over entries), not a column.
 * Nothing in this service stores a running total, because a stored balance and its entries can
 * disagree, and then you have two answers and no truth. If that query ever becomes slow, the fix is
 * a materialised snapshot with a measured refresh strategy - and a performance document explaining
 * the trade-off - not a mutable column added quietly.
 *
 * <h2>4. Idempotency (the part that makes this a distributed-systems project)</h2>
 *
 * Kafka delivers at least once. After a rebalance or a crash between processing and offset commit,
 * the same {@code PaymentCaptured} event arrives again. So the consumer does this, all inside ONE
 * transaction:
 *
 * <pre>
 * BEGIN
 *   INSERT INTO processed_events (event_id, consumer) VALUES (?, 'ledger');  -- PK violation on replay
 *   INSERT INTO ledger_entries ... (the debit)
 *   INSERT INTO ledger_entries ... (the credit)
 * COMMIT
 * </pre>
 *
 * On a duplicate delivery the first INSERT violates the primary key, the transaction rolls back,
 * nothing is posted, and the consumer acknowledges the message anyway. That is the whole trick:
 * <b>at-least-once delivery plus an idempotent consumer equals exactly-once business effects</b>.
 * Say it that way. Do not say the system has exactly-once delivery, because it does not, and the
 * person interviewing you will know.
 *
 * <p>Order matters: insert the marker FIRST. Posting the entries first and marking afterwards is
 * the same transaction and therefore equally atomic here, but the marker-first shape is the one
 * that keeps working if the entries ever move to a different store.
 *
 * <h2>5. What this deliberately is not</h2>
 *
 * No chart of accounts beyond three entries ({@code CUSTOMER_RECEIVABLE}, {@code MERCHANT_PAYABLE},
 * {@code PLATFORM_FEE}), no multi-currency conversion, no accounting periods, no settlement or
 * payout cycle, no partial refunds. Each of those is a real feature of a real ledger and each is
 * out of scope here; say so in the README's Known Limitations section. "I scoped it to the part
 * that demonstrates the principle" is a strong answer. "I ran out of time" is not, and they sound
 * identical unless you wrote the scope down first.
 *
 * <h2>6. TODO Phase 4 (Day 25-28)</h2>
 *
 * <ol>
 *   <li>Make this a JPA entity mapped to {@code ledger_entries} - all fields final-ish, no setters,
 *       no public no-arg constructor beyond what JPA requires.</li>
 *   <li>Add {@code AccountType} and {@code Direction} enums rather than strings in the domain.</li>
 *   <li>Own the schema here: move {@code ledger_entries} and {@code processed_events} out of
 *       payment-service's V1 migration into this service's own Flyway migration and schema.</li>
 *   <li>Write the invariant test first, then the consumer.</li>
 * </ol>
 */
public final class LedgerEntry {

    private LedgerEntry() {
        // TODO Phase 4 (Day 25): replace this placeholder with the real entity described above.
        throw new UnsupportedOperationException("Not implemented until Phase 4");
    }
}
