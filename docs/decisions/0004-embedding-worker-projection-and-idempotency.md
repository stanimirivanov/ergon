# ADR 0004: Embedding worker projection and idempotency

- Status: Superseded by the Ergon rewrite
- Date: 2026-08-20

## Context

Kafka delivery is at least once, model calls can fail, and an article can
publish multiple revisions. Redelivery must not duplicate chunks or status
events, and an older revision must not remain searchable after replacement or
withdrawal.

## Decision

- The worker claims the integration event ID in `embedding_inbox` in the same
  PostgreSQL transaction as vector replacement and the index-status outbox
  record.
- Chunk IDs are deterministic across replays. The outbound adapter uses Spring
  AI's `VectorStore`: before adding a revision it deletes the current documents
  for that tenant/article, then adds the new chunk documents with tenant,
  article, revision, chunk-index, and locale metadata.
- Withdrawal deletes the tenant/article documents from the searchable vector
  projection. The event stream remains the rebuild and audit source.
- Ollama is the default local Spring AI `EmbeddingModel`; a deterministic
  SHA-256-derived adapter is selected for CI and tests.
- The worker uses its own Flyway history table because local development
  currently shares a PostgreSQL schema with ingestion while each service owns
  independent migration versions.

## Consequences

- A failed transaction rolls back the inbox claim, so Kafka redelivery can retry
  the complete operation.
- Successful redelivery becomes a no-op and cannot duplicate status output.
- Retrieval does not need an `active` predicate because stale and withdrawn
  documents are removed transactionally.
- Spring AI owns the pgvector document-table schema while Flyway owns the inbox
  and status-outbox tables. The configured `EmbeddingModel` determines vector
  dimensions; production must keep one model dimension per vector table.
