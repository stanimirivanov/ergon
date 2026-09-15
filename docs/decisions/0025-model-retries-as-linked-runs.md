# 0025. Model retries as linked resolution runs

- Status: proposed
- Date: 2026-09-15
- Owners: Ergon maintainers

## Context

A failed connector receipt is terminal for one authorization consumption and
provider idempotency key. Reusing that run cannot distinguish a replay from a
new external attempt, while mutating its snapshot would erase which evidence,
policy, authority, and action produced the failure.

## Decision

Represent an explicit retry as a new immutable run whose one-based attempt
number directly follows a failed predecessor. Recalculate readiness and policy
at an `If-Match` case version, but require the same contract revision, step, and
capability. Insert the successor and its version-zero state, append
`RETRY_STARTED` to the predecessor, and project the predecessor to `SUPERSEDED`
in one transaction.

Give the successor a new run identity. It copies no approval, grant,
consumption, receipt, or provider idempotency key, so authorization must be
established again. Composite self-references constrain chain adjacency and
operation identity; an event foreign key binds the successor to the exact
sequence-one failure.

## Alternatives

Appending another receipt to the failed run was rejected because the current
receipt and consumption are immutable and idempotent. Resetting run state was
rejected because it would hide the failed attempt. Allowing the retry command to
select a different step or contract was rejected because that is replanning,
not retry. Automatically scheduling a successor was deferred until bounded
retry policy, backoff, and recovery authority exist.

## Consequences

Attempt history remains auditable, every external attempt has fresh authority,
and repeated retry commands return one successor. Existing runs migrate as
attempt one without rewriting immutable rows. ADR 0026 adds authenticated
resolver attribution to newly created retry events. Retry eligibility and
limits, compensation, escalation, and generalized replanning remain later
decisions.

## Verification

Domain tests cover chain and state invariants. PostgreSQL-backed API tests cover
terminal failure, stale and cross-tenant rejection, first-write and replay
semantics, fresh initial requirements, constrained operation identity, linked
state projections, and immutable history. Migration tests exercise empty and
supported upgrade schemas.
