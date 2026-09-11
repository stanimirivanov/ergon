# ADR 0002: Synchronous projections and transactional outbox

- Status: Superseded by the Ergon rewrite
- Date: 2026-08-20

## Context

Article commands must update their event stream, CQRS read model, and
integration-event intent without a dual-write failure window. Kafka availability
must not determine whether an article command can commit.

## Decision

- The ingestion service owns `article_events`, `article_projection`,
  `article_outbox`, and `command_idempotency` in one PostgreSQL database.
- A command appends domain events, updates the projection, inserts externally
  relevant outbox rows, and records its idempotency result in one Spring
  transaction.
- Publication, withdrawal, and restoration create versioned integration
  envelopes. Draft creation and editing remain internal events.
- The publisher claims bounded batches with `FOR UPDATE SKIP LOCKED`, sends them
  through Spring Kafka, and marks them published before releasing the database
  transaction.
- PostgreSQL assigns persistence timestamps. Domain event occurrence remains
  application-clock controlled.
- Projections are disposable and can be rebuilt from the event store through an
  internal replay operation.

## Consequences

- Command reads and article query reads are separated while retaining immediate
  read-after-write behavior.
- Kafka outages accumulate pending outbox rows instead of rolling back already
  accepted domain commands.
- Delivery is at least once: a crash after Kafka accepts a record but before
  `published_at` commits can cause redelivery. Consumers must be idempotent.
- Holding a database lock while waiting for Kafka favors a simple, demonstrable
  relay. A production optimization may introduce leases and delivery attempts
  later.
