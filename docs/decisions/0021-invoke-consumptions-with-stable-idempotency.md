# 0021. Invoke consumptions with stable idempotency

- Status: proposed
- Date: 2026-09-14
- Owners: Ergon maintainers

## Context

A durable authorization consumption reserves work but does not perform it. A
process can fail after a provider accepts a command and before Ergon stores the
result. Holding a database transaction across the remote call would not make
the two systems atomic and would add lock duration and connection pressure.

## Decision

Use the authorization consumption identity as the connector idempotency key.
Read the tenant-scoped consumption and existing receipt in one short
transaction, close it before connector I/O, then store the terminal result in a
second transaction. Replays return an existing receipt without calling the
connector. Concurrent calls may both reach the connector with the same key;
the connector contract and database uniqueness collapse them to one provider
operation and one receipt.

Copy the consumption scope into an immutable receipt and bind the copy with a
composite foreign key. Permit completion after grant expiry because authority
was already spent while current. Treat the receipt as the connector's claim,
not independent outcome proof or current run state.

The first adapter is a deterministic local implementation of
`identity-stub`/`identity.account.unlock`. It derives its provider reference
from the idempotency key and performs no network or credential access.

## Alternatives

A random key generated immediately before each call was rejected because a
crash would lose it and make retry unsafe. Persisting a separate invocation
intent was deferred because the immutable consumption already supplies stable
identity and exact scope for this single-call slice. Holding a database
transaction across connector I/O was rejected because it cannot atomically
commit the provider operation.

## Consequences

An uncertain call can be retried safely only when a connector honors the port's
idempotency contract. A terminal provider result becomes append-only audit
evidence. General action inputs, connector credentials, timeouts and retry
scheduling, run transitions, and independent outcome verification remain
later capabilities. The internal endpoint remains an unauthenticated
development boundary and must not be exposed publicly.

## Verification

Domain tests cover copied scope, stable key derivation, temporal ordering, and
post-expiry completion. Application tests prove connector I/O occurs outside
transactions and durable replay skips the connector. PostgreSQL-backed tests
cover same-tenant execution, cross-tenant absence, replay, exact scope, and
receipt immutability. Migration tests exercise empty and supported previous
schemas.
