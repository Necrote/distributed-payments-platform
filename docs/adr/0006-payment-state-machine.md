# ADR-006: The payment lifecycle is an explicit state machine with one door

- **Status:** Accepted
- **Date:** 2026-10-22 (the design dates from Day 1; this records it, now that it's tested under
  concurrency)
- **Scope:** `payment-service`: `PaymentStatus`, `Payment`, every endpoint that changes a payment
- **Related:** [ADR-004](0004-idempotency-keys.md), idempotency keys. ADR-004 stops a second
  *create*; this ADR stops an illegal or duplicated *transition*.

## Context

A payment moves through a lifecycle (created, authorised, captured, refunded), and most of the
expensive bugs in a payment system are **transitions that should never have happened**:

- refunding money that was only *authorised*, never captured, so there is nothing to give back;
- capturing twice because two requests both saw `AUTHORIZED`;
- a refund that fails at the processor and leaves the payment stuck "pending" forever;
- a status set directly by some new code path that never asked whether the move was legal.

The question isn't "which statuses exist". It's "who is allowed to change a status, to what, and how
do we *know* every illegal move is refused, including when two requests race?"

## Decision

**1. Seven states, and the legal moves are data, not code.**

```
CREATED         -> PROCESSING, FAILED
PROCESSING      -> AUTHORIZED, FAILED
AUTHORIZED      -> CAPTURED, FAILED          (FAILED here is a void)
CAPTURED        -> REFUND_PENDING
REFUND_PENDING  -> REFUNDED, CAPTURED        (CAPTURED here is a rejected refund)
FAILED          -> (terminal)
REFUNDED        -> (terminal)
```

The table is an `EnumMap<PaymentStatus, EnumSet<PaymentStatus>>` in `PaymentStatus`. Everything
asks it, through `canTransitionTo` / `assertCanTransitionTo`.

**2. One door.** `Payment` has no status setter. A status changes only through
`Payment.transitionTo(target, clock)`, which calls the guard first and then sets `status` and
`updatedAt`. A new code path physically cannot skip the check without editing the entity.

**3. Three edges are deliberate, and each has its own test:**

- **`AUTHORIZED → REFUNDED` is illegal.** An authorisation only reserves funds; no money moved. You
  can't refund what you never took. Cancelling an authorisation is a *void*, which here is
  `AUTHORIZED → FAILED`. Processors treat void and refund as different operations because the money
  moves differently, and the ledger has nothing to reverse before `CAPTURED`.
- **`REFUND_PENDING → CAPTURED` is legal.** If the processor rejects a refund, the money is still
  captured. Without this edge the payment is stranded in `REFUND_PENDING` forever. It's also why a
  refund is its own entity with its own attempts, not a boolean on the payment.
- **No transition to the same state.** Re-entering a state would hide a bug ("captured twice"
  looking like success). It would also increase the optimistic-lock version for no business reason,
  which makes real conflicts look more common than they are.

**4. Statuses are stored as strings, and the database knows the list too.** `@Enumerated(STRING)`,
never ordinal: reordering the enum must not reinterpret stored rows, and `psql` at 3 a.m. must be
readable. The `payments_status_known` CHECK constraint repeats the seven names, so a buggy deploy or
a hand-written `UPDATE` can't store a status the code doesn't know.

**5. Concurrency is a separate defence: an optimistic version column.** The table checks a move
against the status *this request read*. It can't know whether that status is still current at write
time. Two captures can both read `AUTHORIZED` and both pass. `@Version` on `Payment` adds
`AND version = ?` to every UPDATE, so the second writer matches zero rows and fails.

**6. Both kinds of refusal are a `409`, and neither is retried.**

| The request… | Caught by | Response |
|---|---|---|
| asks for an illegal move (capture a `CREATED` payment) | the table | `409` "Illegal payment state transition", with `currentStatus` |
| read a status that a concurrent request has since changed | the version column | `409` "Concurrent update": nothing applied, fetch before retrying |

A version conflict is **not auto-retried**. On capture it means someone else already captured.
A retry would re-read `CAPTURED` and fail anyway, and once a processor call lives in this path, a
retry loop is how a loser fights a legitimate winner for a second capture.

### Alternatives rejected

- **`if`/`else` (or `switch`) in each service method.** It has the same behaviour on the happy path
  and a far weaker guarantee. The rules are scattered across methods, so you can't list them, and
  you can't exhaustively test the *denied* cases. A test that covers the six legal moves leaves
  every "this must be refused" mutant alive.
- **The State pattern (a class per state).** It's the right tool when behaviour differs a lot per
  state. Here the only per-state question is "where may I go next", which is a lookup, not
  polymorphism. Seven classes to express seven table rows is more code and harder to see at once.
- **A state-machine library (e.g. Spring Statemachine).** It brings events, guards, actions,
  persistence adapters and its own learning curve for a seven-row table. It would also hide the one
  thing this ADR wants visible: the whole lifecycle readable in ten lines.
- **Enforce it only in the database** (a trigger that checks old → new). It's the last line of
  defence, not the first. Errors arrive as SQL exceptions after the work is done, the rules live
  outside the code reviewers read, and a trigger can't produce a helpful `409`. The CHECK constraint
  is kept as a backstop for *unknown* values, not *illegal moves*.
- **Pessimistic locking (`SELECT … FOR UPDATE`) instead of a version column.** That's right for
  hot rows with many writers. A payment row is the opposite: two writers on one payment is an
  anomaly, and the transaction is short. Optimistic locking costs nothing on the normal path, and the
  rare loser fails fast instead of queueing.

## Consequences

**What this gives us**

- **Every one of the 49 ordered pairs is tested.** `PaymentStatusTest` walks all 7 × 7 pairs and
  asserts allowed or denied for each. The expected table is restated *independently* in the test (a
  `switch`, not a read of the production map), so a bug in the map can't agree with itself.
  Changing the lifecycle means editing both, which is deliberate friction.
- **The tests are proven to notice changes, not just to run.** PIT kills 9 of 10 mutants in
  `PaymentStatus`. Adding an illegal edge on purpose (Day 2) failed three tests independently.
  Mutation testing on Day 11 found the one real gap: deleting the guard call from
  `Payment.transitionTo` broke no unit test, because the line ran in other tests but nothing asserted
  a refusal. `PaymentTest` now asserts that `CREATED → CAPTURED` throws *and* leaves the status and
  `updatedAt` untouched.
- **Exactly one capture under a race.** Two simultaneous captures give one `200` and one `409`,
  version `2` (create, authorise, one capture), over 20 runs. A deterministic twin forces both
  transactions to hold a stale `AUTHORIZED` before either commits, using a barrier after the read,
  so the loser *must* hit the version check. With `@Version` removed, all 40 runs fail, and the HTTP
  race returns `[200, 200]`, which is the lost update made visible.

**What this makes hard**

- **Adding a state touches three places, on purpose:** the enum's table, the test's independent
  table, and the database CHECK constraint, through a Flyway migration. The migration must ship
  *before* any code that writes the new status, or the first write fails the CHECK. Use
  expand/contract: widen the constraint, deploy the code, and never the other way round.
- **One equivalent mutant can't be killed.** `if (target == null) return false` in
  `canTransitionTo` is redundant, because `EnumSet.contains(null)` is already `false`. PIT's mutant
  of it behaves identically. It's kept as documentation, and the honest mutation ceiling for this
  class is 9/10.
- **Seven states is deliberately not the real world.** There's no partial refund, no dispute or
  chargeback, and no authorisation expiry. Each is a real lifecycle of its own (disputes especially),
  and adding them here would mean more paths to test without making the design more convincing. The
  README lists them as limitations.
- **`PROCESSING` carries a promise the code doesn't keep yet.** It means "an authorisation is in
  flight, outcome unknown". A processor timeout must *not* move it to `FAILED` until the outcome is
  confirmed or reconciled, because a timeout isn't a decline. That rule belongs to Phase 3's
  processor integration. Until then, nothing enters `PROCESSING` except the temporary admin
  `force-authorize` endpoint, which is off by default and deleted in Phase 3.
- **The loser of a race has to look before it retries.** Clients must treat a `409` as "fetch the
  payment, then decide". A client that blindly retries on `409` will just get another `409`. That's
  safe, but it needs to be in the API documentation.

## Status of the pieces

| Piece | State |
|---|---|
| Transition table + guard, all 49 pairs tested | Built, tested (`PaymentStatusTest`, PIT 9/10) |
| One door (`Payment.transitionTo`), refusal leaves state untouched | Built, tested (`PaymentTest`) |
| Illegal move → `409` with `currentStatus` | Built, tested (controller test, IT) |
| Concurrent update → `409`, not retried | Built, tested (20-run HTTP race, deterministic twin) |
| CHECK constraint on known statuses | Built (`V1__core_schema.sql`) |
| `PROCESSING` timeout semantics, refund confirmation | Phase 3 |
| Refund race test | Not yet: same code path as capture, not separately proven |
