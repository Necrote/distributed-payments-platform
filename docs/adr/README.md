# Architecture decision records

One page per decision that someone would otherwise have to reverse-engineer from the code: what was
decided, what was rejected, and what it makes hard. Numbers are reserved by topic and filled in as
each decision is made and tested, so gaps in the list are decisions not yet written, not deleted
ones.

| ADR | Decision | Status |
|---|---|---|
| [004](0004-idempotency-keys.md) | Idempotency keys enforced by a database unique constraint, not a cache | Accepted · revised 2026-10-22 |
| [006](0006-payment-state-machine.md) | The payment lifecycle is an explicit state machine with one door; races settled by an optimistic version | Accepted |
