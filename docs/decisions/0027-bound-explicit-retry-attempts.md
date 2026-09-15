# 0027. Bound explicit retry attempts

- Status: proposed
- Date: 2026-09-15
- Owners: Ergon maintainers

## Context

Resolver authority alone does not bound repeated external work. Linked run
identity protects idempotency, but an unlimited chain can repeat a failing
operation indefinitely. The eligibility decision must also remain auditable
after its rules change.

## Decision

Use deterministic policy `ergon.dev/policy/resolution-retry/v1` with two total
attempts, including the initial run. An unsuperseded failed attempt below that
ceiling may start its direct successor; a failure at or above it returns a stable
conflict without allocating identities or writing state.

Record policy revision, source attempt, and total ceiling on every new
`RETRY_STARTED` event. Domain invariants and a tenant-safe database foreign key
bind eligibility to the failed run's actual attempt; a check constraint enforces
that the source is below the ceiling. The intentionally redundant run uniqueness
supports that composite foreign key. Legacy events retain absent policy fields.

Check replay before current eligibility. Current resolver authority still applies
to replay, but changing the ceiling cannot invalidate a previously recorded
successor. Policy is code-defined rather than client-configurable in this slice.

## Alternatives

Unbounded retries were rejected because authorization is not a recovery budget.
A client-supplied limit was rejected because the caller must not choose its own
bound. Failure-specific rules, tenant overrides, scheduling, and backoff were
deferred until their durable contracts can be delivered and verified separately.

## Consequences

The current chain permits one explicit retry. Exhaustion leaves the run
`ACTION_FAILED` and case open; it does not compensate, resolve, or escalate.
Future rule changes require a new revision and explicit compatibility review.
The additive migration preserves old application writes and immutable history;
deploy the migration before the new writer. It builds one index and validates
constraints under table locks, so large deployments must budget those scans.

## Verification

Domain and application tests cover the ceiling, invalid positions, source
binding, and replay under a stricter policy, including legacy events.
PostgreSQL-backed HTTP tests execute both attempts with fresh authorization and
verify that a failed second attempt cannot create a third run. Migration tests
exercise empty and supported upgrade schemas.
