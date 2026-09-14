# 0019. Derive bounded capability authorization grants

- Status: proposed
- Date: 2026-09-14
- Owners: Ergon maintainers

## Context

An approved human decision satisfies one policy prerequisite but must not be a
reusable permission. Execution needs a separate record that fixes the exact
scope being authorized and limits how long the approval can be acted upon.

## Decision

Allow one grant per approved decision only while its approval request remains
current. Derive all scope server-side from immutable history: tenant, decision,
request, run, case, policy revision, step, and capability. Inherit the request's
exclusive expiry boundary rather than introducing a caller-selected or renewed
lifetime.

Lock the decision and insert the grant in one transaction. Store copied scope
under composite foreign keys, require the linked outcome to be `APPROVED`, and
prevent grant update or deletion. Treat the grant ID as an audit identity, not
a bearer credential. Consumption and execution are separate future boundaries.

## Alternatives

Treating `APPROVED` as authorization was rejected because it lacks explicit
capability scope and consumption semantics. A fresh fixed grant lifetime was
rejected because it could revive stale approval after the request expired.
Accepting capability or expiry in the command was rejected because callers must
not broaden policy-derived authority.

## Consequences

Authorization remains narrow, tenant-scoped, time-bounded, and independently
auditable. Retries identify the existing grant. The current internal endpoint
retains the repository's temporary unauthenticated development boundary and
must not be exposed externally. Single-use consumption, caller authorization,
capability availability, connector credentials, invocation, idempotency,
receipts, revocation, and run advancement remain future decisions.

## Verification

Domain tests cover exact scope, approved outcome, expiry, and source mismatch.
PostgreSQL-backed API tests cover derivation, tenant isolation, rejection,
uniqueness, copied scope, inherited expiry, and immutability. Migration tests
exercise empty and supported previous schemas.
