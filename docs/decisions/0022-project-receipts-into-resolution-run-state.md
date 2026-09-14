# 0022. Project receipts into resolution-run state

- Status: proposed
- Date: 2026-09-14
- Owners: Ergon maintainers

## Context

A connector receipt is immutable evidence that an authorized action reported a
terminal result, but it is not convenient current workflow state and it is not
independent proof of the requested outcome. Updating the immutable run start
would erase that distinction. Deriving state only when queried would make later
serialized commands harder to guard and would repeatedly interpret history.

## Decision

Keep the run start immutable. Seed a tenant-scoped current-state projection at
version zero in the same transaction that creates the run. Project one terminal
receipt by locking that row, appending an immutable sequence-one event, and
updating the projection to version one in the same transaction.

Bind the event to the exact receipt with run identity, outcome, and completion
time constraints. A successful receipt maps to `CAPABILITY_SUCCEEDED` and
`VERIFYING`; a failed receipt maps to `CAPABILITY_FAILED` and `ACTION_FAILED`.
Neither mapping changes case status. Receipt identity provides replay
idempotency, so a repeated command returns the original event.

The current vertical slice permits one capability receipt and result event per
run. This makes run-based receipt lookup deterministic until ordered multi-step
execution has an explicit model.

## Alternatives

Mutating the run-start row was rejected because requirements at creation and
current execution state have different meanings. Treating connector success as
resolution was rejected because a provider claim cannot prove the user's goal.
Rebuilding current state from events on every command was deferred because the
transactional projection gives simple row locking while events retain the
rebuildable source of truth.

## Consequences

Commands can serialize on one narrow row, while audit history stays append-only.
The projection is disposable and must always agree with event sequence. The
schema is deliberately narrow: verification events, retries, compensation,
multiple steps, terminal resolution, and projection rebuilding are not yet
defined. The internal endpoint remains a development boundary and must not be
exposed publicly.

## Verification

Domain tests cover success and failure mappings plus run and sequence
invariants. Application and PostgreSQL-backed tests cover replay, missing and
cross-tenant sources, transactional projection, immutable history, unchanged run
start, and an open case after connector success. Migration tests exercise empty
and supported previous schemas.
