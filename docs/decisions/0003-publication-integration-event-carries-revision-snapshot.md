# ADR 0003: Publication integration events carry revision snapshots

- Status: Superseded by the Ergon rewrite
- Date: 2026-08-20

## Context

The embedding worker needs the exact published title, body, locale, collection,
and revision to produce a replayable vector projection. Publishing only a
revision number would force a synchronous callback into the ingestion service
and could return content newer than the event being processed.

## Decision

- `ArticlePublished` and `ArticleRestored` integration envelopes contain the
  immutable revision snapshot: revision, title, body, locale, and tenant-scoped
  collection ID.
- Domain events remain minimal and continue to represent aggregate decisions.
  The outbox adapter maps them to independently versioned integration contracts.
- `ArticleWithdrawn` carries the affected revision and aggregate metadata but
  does not repeat article content.
- Consumers use the envelope event ID for inbox idempotency and deterministic
  chunk IDs derived from tenant, article, revision, index, and content.

## Consequences

- The embedding worker can process and replay publication events without calling
  the command service.
- Integration messages are larger and may contain help-center content; Kafka
  retention and access controls must be treated accordingly.
- Contract evolution is separate from domain-event serialization and requires
  schema-version compatibility tests.
