# 0015. Store expiring approval requests without authority

- Status: proposed
- Date: 2026-09-13
- Owners: Ergon maintainers

## Context

A run can state that requester or resolver approval is required, but it cannot
wait safely on an unbounded or mutable prompt. Approval requests need an audit
identity, a bounded validity interval, and duplicate protection. They must not
be mistaken for an approval or carry an unverified actor supplied by a channel.

## Decision

Store each request as an immutable tenant-scoped record tied by foreign key to
the exact run, step, and required authority. Use application-clock
`requestedAt` and exclusive `expiresAt` instants for domain meaning and retain a
separate database `recordedAt` instant. Derive `PENDING` or `EXPIRED` when read;
do not mutate status or require a timer merely because time passes.

Use a built-in 15-minute lifetime and enforce a 24-hour domain and database
maximum. Callers cannot supply expiry. Permit only runs in
`WAITING_FOR_APPROVAL`, and represent authority as `REQUESTER` or `RESOLVER` so
`NONE` cannot appear on a request.

Lock the run row and inspect its latest request in the creation transaction. A
still-valid request returns a conflict containing its identity and expiry. An
expired request remains in history and permits an appended replacement. This
lock-based rule serializes concurrent callers; a time-dependent partial unique
index cannot correctly encode it.

## Alternatives

Updating a stored status to `EXPIRED` was rejected because time already
determines expiry and mutable status can lag reality. Reusing or deleting an
expired row was rejected because it destroys audit history. Caller-selected
lifetimes were rejected because channels are not policy authorities. Creating
an approval and request together was rejected because actor identity and role
evidence do not exist yet.

## Consequences

The platform can display a bounded approval prompt, reject concurrent or
duplicate prompts, and renew after expiry without losing history. Tenant keys
and the composite run foreign key prevent a request from changing its run's
step or authority.

This increment does not authenticate actors, accept approval decisions, advance
run state, authorize a capability, or notify users. A later decision must define
actor identity, proof of requester/resolver role, grant/rejection events, and
the relationship between grant validity and request expiry.

## Verification

Domain tests prove role mapping, the exclusive expiry boundary, rejection of
no-approval runs, and maximum lifetime. PostgreSQL-backed API tests prove create
and read behavior, exact 15-minute validity, active conflict, renewal after
expiry, retained history, tenant isolation, immutability, OpenAPI publication,
and empty/upgrade migration paths.
