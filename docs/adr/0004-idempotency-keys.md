# ADR-004: Idempotency keys enforced by a database unique constraint

- **Status:** Accepted
- **Date:** 2026-10-13
- **Scope:** `payment-service`, `POST /payments`

## Context

A client that times out on `POST /payments` can't tell whether the payment was created. Its only
safe move is to retry, and the API has to make that retry harmless. Retries are also not polite or
sequential. A client library with a short timeout and an impatient user can send the second attempt
while the first one is still running.

Three things have to be true at once:

1. **A retry never creates a second payment**, even when it arrives while the first is in flight.
2. **A retry gets the original answer**, not a fresh rendering of whatever state the payment is in now.
3. **A reused key with a different request is rejected**, never silently answered with the first
   payment.

## Decision

- The client sends an `Idempotency-Key` header. Keys are scoped per merchant:
  `UNIQUE (merchant_id, idempotency_key)` on `idempotency_keys`.
- Each request is fingerprinted as the SHA-256 of its **parsed** body, serialised canonically: keys
  sorted at every level, no whitespace, nulls kept (`CanonicalRequestHash`). The serialiser is
  private to the hasher, not Spring's shared `ObjectMapper`.
- The key row is inserted **in the same transaction as the payment**, and flushed first. The response
  status and body are written to that row before commit.
- A duplicate-key violation on that constraint is the replay path, not an error path. It is caught
  **outside** the failed transaction (`IdempotentPaymentCreator`, `Propagation.NEVER`), and the stored
  row is read in a new read-only transaction. Then:
  - **hash matches** → `200 OK`, the stored body, `Idempotent-Replayed: true`;
  - **hash differs** → `422 Unprocessable Entity`, a ProblemDetail naming the original payment and
    the differing fields. Nothing is written and nothing is replayed.
- Keys expire after 24h (`payments.idempotency.key-ttl`) and are swept by `expires_at`.

### Alternatives rejected

- **Check-then-insert** (`SELECT`, then `INSERT` if absent). Two concurrent requests can both see
  nothing in the gap, and both insert. Only the constraint is atomic.
- **Redis as the idempotency store.** It's fast, but two instances can both miss under concurrency,
  and it's not durable in the same transaction as the payment. Redis may come later as a read
  accelerator (Phase 5), never as the guarantee.
- **Reserve the key in its own transaction, then create the payment.** Between the two commits a key
  exists with no payment behind it. A retry in that gap can't tell "in progress" from "crashed". The
  only way out is to let a retry take over the key, and if the first request was slow rather than
  dead, both create a payment.
- **Hash the raw request bytes.** A retry that re-serialises its JSON with a different key order or
  different whitespace would be refused as a different request.
- **Replay the first payment when the body differs.** This is the dangerous one. See Consequences.

## Consequences

**What this gives us**

- Exactly one payment per (merchant, key) within the TTL, decided by PostgreSQL. A request that
  arrives while the first is in flight blocks on the unique index until the first commits (then
  replays) or rolls back (then creates). This is proven by `PaymentApplicationServiceIT`, which holds
  the first transaction open, and by 8 simultaneous requests producing exactly one payment.
- A replay is the original response, byte-identical, even if the payment has moved on since.
- **A reused key with a different body fails loudly.** The client gets a `422` that says which
  payment the key already belongs to, which fields differ, and the two ways out (resend the original
  body, or use a new key). Field names only, never values. A changed card token is named by
  elimination and never echoed, because the stored response doesn't contain it.

  This is the property most implementations get wrong, and the reason it's a hard error is the
  cost asymmetry. Say the first request charged ₹125.00 and the reused key carries ₹9,999.00. A
  silent replay returns `200` and the payment. The client's books now say ₹9,999 was charged, ours
  say ₹125, and the mismatch surfaces weeks later in reconciliation with nothing pointing back at
  the cause. A `422` costs one failed request, and it fails *at the bug*.

**What this makes hard**

- **The transaction boundary is a trap.** It's tempting to catch the duplicate-key exception inside
  `create` and look up the stored row there. That fails three ways, all observed: PostgreSQL has
  aborted the transaction ("current transaction is aborted, commands ignored…"); Hibernate's
  auto-flush re-sends the failed INSERT before the lookup; and the commit throws
  `UnexpectedRollbackException`. Recovery has to live a layer up, in a fresh transaction.
  `REQUIRES_NEW` would also work, but it holds two pooled connections per replaying request, which
  under a retry burst can deadlock a pool of 10. `Propagation.NEVER` on the creator stops anyone from
  wrapping it back into an outer transaction.
- **Hash stability is now a compatibility contract.** A stored hash is only comparable with one
  computed by the same canonicalisation. Changing it (the fingerprint's fields, null handling, the
  serialiser's settings) turns every key still inside its 24h window into a `422` for an honest
  retry. Changes to `CanonicalRequestHash` or `CreatePaymentFingerprint` need a versioned hash, or
  have to wait out the TTL. `CanonicalRequestHashTest` pins a reference vector so that such a change
  can't slip in unnoticed.
- **"Differing fields" is best-effort.** Only the hash of the original request is stored, so the
  fields are recovered from the stored *response* (`amount`, `currency`, `externalReference`), and
  the token is inferred by elimination. If a future field is hashed but not echoed in the response,
  the 422 can't name it.
- **The waiting request holds a connection.** A retry that blocks on the index holds its pooled
  connection for as long as the first transaction runs. That's cheap today, because nothing slow
  happens inside the transaction. When the processor call arrives (Phase 3), it **must not** be
  inside this transaction, or every concurrent retry waits for the processor while holding a
  connection.
- **Replay shows the past, not the present.** A client that wants the payment's current status has to
  `GET /payments/{id}`. That's deliberate, and it has to be documented for API users.
- **Unbounded growth is now our problem.** This is the hottest write table in the service. The
  expiry sweep isn't built yet. Until it is, rows past `expires_at` stay, and they still replay.

## Status of the pieces

| Piece | State |
|---|---|
| Key + payment in one transaction | Built, tested (rollback IT) |
| Replay (200, original body) | Built, tested (HTTP IT, in-flight ITs, 8-thread IT) |
| Different body → 422 with differing fields | Built, tested (HTTP IT, unit tests) |
| `Idempotency-Key` required on `POST /payments` | Not yet: requests without a key still create a payment |
| Expiry sweep | Not built |
