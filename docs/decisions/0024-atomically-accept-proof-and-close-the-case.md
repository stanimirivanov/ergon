# 0024. Atomically accept proof and close the case

- Status: proposed
- Date: 2026-09-15
- Owners: Ergon maintainers

## Context

Outcome assessment is deliberately mutable: later evidence can replace an
earlier matching fact. Completion needs one durable decision that identifies
the exact evidence accepted and cannot leave a completed run paired with an
open case, or the inverse.

## Decision

Accept proof only through a command while the run is locked in `VERIFYING` at
version one. Reassess the pinned contract condition, then append an
`OUTCOME_PROOF_ACCEPTED` run event at sequence two and a linked
`CaseVerifiedResolved` event immediately after the assessed case version.
Advance both projections to `VERIFIED_RESOLVED` in the same transaction.

Persist an immutable acceptance projection containing the case snapshot,
observation, semantic fact, expected value, and stream positions. Foreign keys
bind it to the same tenant, run, case, observation, and account-state fact. If
the case stream changes before append, roll back instead of accepting stale
proof. Replay returns the original acceptance.

## Alternatives

Closing directly from the assessment query was rejected because reads must not
hide durable workflow changes. Separate run and case transactions were rejected
because partial completion creates contradictory authority and lifecycle state.
Trusting only application identifiers was rejected because the database can
independently bind the accepted value to its source projection.

## Consequences

Successful access restoration now has a terminal, auditable happy path. Closed
cases reject later observations, bindings, and contract pins. The initial model
still permits one run per case and one account-state proof condition; retries,
reopening, and failed-action recovery require explicit later designs.

## Verification

Domain tests cover terminal run-event shape, exact case-version closure, and
post-closure mutation rejection. The end-to-end flow covers pending proof,
later correcting evidence, tenant isolation, first-write/replay semantics,
immutable persistence, and synchronized terminal run and case state. Migration
tests cover both empty-schema creation and upgrade from the supported baseline.
