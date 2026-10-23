# ADR-004: Idempotency keys enforced by a database unique constraint

- **Status:** Accepted
- **Date:** 2026-10-13 · **Revised:** 2026-10-22 (what the week's tests taught; see [Revision notes](#revision-notes))
- **Scope:** `payment-service`, `POST /payments`
- **Related:** [ADR-006](0006-payment-state-machine.md), the payment state machine. Idempotency
  stops a *second create*; ADR-006 stops a *second transition*.

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
  - **hash matches** → `200 OK`, the stored body, `Idempotent-Replayed: true`. It's `200` and not
    `201` because this request created nothing;
  - **hash differs** → `422 Unprocessable Entity`, a ProblemDetail naming the original payment and
    the differing fields. Nothing is written and nothing is replayed.
- **Only this constraint means "already used".** The catch matches the constraint name
  `uq_idempotency_key_per_merchant`, found by walking the exception's cause chain. Any other
  integrity failure is rethrown unchanged and never turned into a lookup.
- **The stored response is replayed through the DTO, not as raw text.** `response_body` is
  `JSONB`, and JSONB reorders object keys. So the stored body is read back into `PaymentResponse`
  and serialised again by the same mapper that produced the original, which makes the replay
  byte-identical to the first response.
- Keys expire after 24h (`payments.idempotency.key-ttl`) and are swept by `expires_at`.

### Alternatives rejected

- **Check-then-insert** (`SELECT`, then `INSERT` if absent). Two concurrent requests can both see
  nothing in the gap, and both insert. Only the constraint is atomic.
- **Redis as the idempotency store.** It's fast, and each of these failures permits a double charge:
  - **It isn't in the payment's transaction.** "Write the key to Redis, then commit the payment" is
    two systems and two commits. Crash between them and they disagree, which is the reserve-then-
    create problem below, now across a network.
  - **Failover loses recent writes.** Redis replication is asynchronous. A primary that acknowledged
    a key and then died before replicating it leaves a promoted replica that has never seen it. The
    retry looks new.
  - **Eviction deletes keys on purpose.** Under `allkeys-lru` (the usual setting for a cache), memory
    pressure evicts keys whether or not their TTL is up. A key evicted mid-window means a retry
    looks new.

  Each of these shows up as "a retry was treated as new", which is a second charge. PostgreSQL's
  constraint has none of these failure modes: it commits with the payment, it's durable, and it's
  never evicted. Redis may come later as a fast first look (Phase 5). The constraint stays the final
  safeguard, and a cache miss falls through to it.
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
  replays) or rolls back (then creates). The evidence:
  - **Both in-flight outcomes, deterministically.** `PaymentApplicationServiceIT` holds the first
    transaction open, confirms through `pg_stat_activity` that the retry is waiting on a lock (not
    merely slow), then commits (the retry replays) or rolls back (the retry creates).
  - **20 identical requests over HTTP**, released together by a latch: one `201`, nineteen `200`s,
    20 identical ids, one row in `payments` (`PaymentControllerIT`). It also passes with 8 threads
    calling the service directly.
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

  There are **two defences** here, and an experiment showed which test guards which. With key
  sorting switched off, 4 of 5 hash unit tests failed, but the HTTP test with a reordered,
  re-spaced body *still passed*. That's because the client's key order never reaches the hash: the
  body is parsed into a record first. Parsing protects against the *client's* formatting. Sorting
  protects against *ours*: nested maps, or someone reordering the fingerprint's fields in a
  refactor. Both are needed.
- **"Differing fields" is best-effort.** Only the hash of the original request is stored, so the
  fields are recovered from the stored *response* (`amount`, `currency`, `externalReference`), and
  the token is inferred by elimination. If a future field is hashed but not echoed in the response,
  the 422 can't name it.
- **The waiting request holds a connection.** A retry that blocks on the index holds its pooled
  connection for as long as the first transaction runs. That's cheap today, because nothing slow
  happens inside the transaction. The 20-thread test runs at **twice the pool size (10)** on
  purpose: up to ten requests hold connections while they wait, and the rest queue for one. It
  passes only because the winner commits in milliseconds. When the processor call arrives
  (Phase 3), it **must not** be inside this transaction, or every concurrent retry waits for the
  processor while holding a connection. That test will be the first thing to time out if it is.
- **Replay shows the past, not the present.** A client that wants the payment's current status has to
  `GET /payments/{id}`. That's deliberate, and it has to be documented for API users.
- **Unbounded growth is now our problem.** This is the hottest write table in the service. The
  expiry sweep isn't built yet. Until it is, rows past `expires_at` stay, and they still replay.
- **Recognising "our" violation depends on Hibernate's exception shape.** The constraint name comes
  from Hibernate's `ConstraintViolationException` in the cause chain. A Hibernate or driver upgrade
  that changed that shape would turn every replay into a rethrown error (a 500). That's loud, not
  silent, and the replay ITs would catch it first. Mutation testing found the opposite edge: an
  integrity error with *no* Hibernate cause used to be untested. It's now pinned to be rethrown
  unchanged.

## Status of the pieces

| Piece | State |
|---|---|
| Key + payment in one transaction | Built, tested (rollback IT) |
| Replay (200, original body, byte-identical) | Built, tested (HTTP IT, in-flight ITs, 8-thread and 20-thread ITs) |
| Different body → 422 with differing fields | Built, tested (HTTP IT, unit tests) |
| `Idempotency-Key` required on `POST /payments` | Not yet: requests without a key still create a payment |
| Expiry sweep | Not built |

## Revision notes

**2026-10-22.** Revised after a week of building and breaking it. Nothing in the Decision changed
direction. These were added:

- **Redis.** The rejected-alternative paragraph now names the specific failure modes (async
  replication on failover, `allkeys-lru` eviction, no shared transaction) instead of "it can miss".
- **Replay rendering.** Why the replay goes through the DTO: JSONB reorders keys, and raw text would
  be the same document with different bytes.
- **Evidence.** The 20-thread HTTP test, and the `pg_stat_activity` check that makes the in-flight
  test prove blocking rather than assume it.
- **Hash stability.** The experiment that separated the two defences: parsing first, and sorting.
- **The connection-pool consequence**, quantified by running the 20-thread test at twice the pool.
- **Matching our own constraint**, and the cause-chain edge that mutation testing exposed.

The transaction-boundary trap was in the first version. It's kept as written because it was
observed, not predicted: all three failures were reproduced against PostgreSQL before this ADR
described them.
