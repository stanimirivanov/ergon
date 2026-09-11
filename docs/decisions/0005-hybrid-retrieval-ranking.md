# ADR 0005: Hybrid retrieval ranking

- Status: Superseded by the Ergon rewrite
- Date: 2026-08-20

## Context

Cosine similarity and PostgreSQL full-text rank have different scales, so adding
or averaging their raw values would produce model- and query-dependent results.
Both retrieval channels must also enforce tenant and locale isolation before
candidates reach the application layer.

## Decision

- Spring AI `VectorStore` provides the semantic ranked list after applying the
  minimum similarity threshold.
- PostgreSQL `websearch_to_tsquery` and `ts_rank_cd` provide the lexical ranked
  list from the same vector document table.
- Both adapters construct mandatory tenant filters and optional locale filters.
- `SearchKnowledgeService` combines the lists using reciprocal-rank fusion with
  rank constant 60, deduplicates by deterministic chunk ID, normalizes the fused
  score to `[0, 1]`, and applies `topK` after fusion.

## Consequences

- Fusion compares rank positions rather than incompatible raw score scales.
- A chunk found by both channels ranks ahead of otherwise similar single-channel
  candidates.
- Returned scores describe normalized fusion strength, not cosine similarity.
- Retrieval evaluation must treat the rank constant and normalization as a
  versioned ranking policy.
