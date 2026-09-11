# ADR 0001: Event time and validation ownership

- Status: Superseded by the Ergon rewrite
- Date: 2026-08-20

## Context

An event-sourced article has both business facts and persistence metadata. It is
tempting to let PostgreSQL generate every timestamp and enforce every article
rule, but that would make the event-store schema responsible for the meaning of
versioned JSON payloads. It would also make domain behavior depend on a database
connection and make historical imports or delayed events indistinguishable from
newly occurring events.

Validation is needed by HTTP callers, the aggregate, and the database, but those
layers protect different boundaries.

## Decision

### Time

- `occurred_at` records when the domain event happened. The application obtains
  it from an injected `Clock` once per command and supplies it to the aggregate.
- `recorded_at` records when PostgreSQL persisted the event. PostgreSQL supplies
  it with `default clock_timestamp()`.
- These timestamps are deliberately separate because events may be delayed,
  retried, imported, or migrated.
- Domain tests use explicit instants. Application tests may replace the `Clock`
  with a fixed clock.

### Identity

- Aggregate IDs are created through the application port `ArticleIdGenerator`.
- The production adapter currently uses random UUIDs. Tests and future ID
  strategies can provide deterministic implementations.
- Event IDs remain application-generated because they identify events across
  persistence and messaging boundaries.

### Validation

- The HTTP adapter validates request shape and produces useful client errors
  with Jakarta Validation.
- Domain value objects and aggregates enforce business invariants regardless of
  the calling adapter. Invalid content reports explicit domain violations
  instead of relying on generic constructor assertions.
- PostgreSQL protects the generic event envelope: identifiers, positive
  versions, non-empty event types, object-shaped JSON payloads, timestamps, and
  stream-version uniqueness.
- PostgreSQL does not validate fields inside versioned event JSON such as title
  length or locale syntax. Doing so would couple the event store to every
  historical payload schema and complicate replay and upcasting.

## Consequences

- Replaying or importing events preserves their true business time while
  recording when they entered this store.
- Domain behavior remains testable without Spring or PostgreSQL.
- Validation is intentionally duplicated at the HTTP and domain boundaries for
  fast feedback and invariant safety.
- Database constraints remain stable as domain event payloads evolve.
- Projections may enforce additional relational constraints because they
  represent the current query model rather than immutable historical event
  schemas.
