# 0020. Consume authorization through tenant capability routes

- Status: proposed
- Date: 2026-09-14
- Owners: Ergon maintainers

## Context

A grant identifies authorized work but must be spent once before execution.
System registration of a capability name does not prove that a tenant has a
connector route for it, and accepting a connector from the consuming caller
would let that caller redirect authorized work.

## Decision

Store one immutable connector route per tenant and capability. Consume a grant
only when it remains within its half-open validity interval and its tenant has
an exact route for the authorized capability. The command accepts no body.

Lock the grant through consumption insertion. Check existing consumption and
expiry before route lookup, then copy the grant scope, validity interval, and
selected connector into an immutable consumption. Composite foreign keys bind
that copy to the same grant and tenant route; uniqueness permits one
consumption per grant under concurrency.

A consumption is an irrevocable reservation for a later connector attempt. It
is neither an invocation nor a success receipt. Connector I/O must occur after
the database transaction and refer to the consumption identity.

## Alternatives

Using the system contract-reference registry as tenant availability was
rejected because vocabulary and installation have different lifecycles.
Accepting a connector in the command was rejected because callers must not
redirect authority. Calling the connector inside the consumption transaction
was rejected because remote latency and failure cannot participate safely in a
database transaction.

## Consequences

Expired, absent, cross-tenant, unavailable, and already-spent grants fail
closed. A durable consumption can later drive retry-safe dispatch after a
process failure. Route provisioning, replacement, health, and revocation are
not yet modeled; this slice supplies only the read boundary and schema needed
for runtime enforcement. The internal endpoint retains the temporary
unauthenticated development boundary and must not be exposed publicly.

## Verification

Domain tests cover copied scope, pre-authorization time, exclusive expiry, and
route mismatch. PostgreSQL-backed tests cover tenant route isolation,
unavailable capability, successful consumption, duplicate consumption, and
immutable rows. Migration tests exercise empty and supported previous schemas.
