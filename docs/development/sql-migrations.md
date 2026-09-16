# SQL schema and migration criteria

## TL;DR

- PostgreSQL and Flyway are the source of truth for production schema evolution.
- Keep every migration focused, forward-compatible, immutable after sharing,
  and safe for mixed-version deployment.
- Use uppercase SQL keywords, unquoted `lower_snake_case` identifiers, explicit
  constraints, and tenant-safe keys.
- Explain durable data meaning and non-obvious operational costs.
- Use expand, migrate, verify, and contract for breaking changes.
- Verify the full chain from an empty database and the supported upgrade state.

## Ownership and Flyway

Flyway versioned SQL migrations own the production schema. Spring/Hibernate
schema creation or mutation is disabled outside disposable tests.

Name migrations `V<UTC timestamp>__<lower_snake_case_description>.sql`, for
example `V20260916093000__create_human_follow_up_work_items.sql`. Allocate the
timestamp from current repository state. If concurrent work collides, refresh
and rename the later unshared migration rather than merging different meanings
under one version.

Never edit, rename, reorder, or remove a migration after it reaches a persistent
shared environment. Add a forward migration. Flyway checksums and history are
compatibility evidence, not obstacles to bypass.

## Scope and evolution

- One migration represents one coherent schema capability or data transition.
- The migration and application behavior that depends on it share one delivery
  plan, even when rollout spans several releases.
- Prefer additive changes that current and next application versions can both
  tolerate.
- Use expand, migrate, verify, and contract for renames, type changes, table
  replacement, constraint tightening, or removal.
- Keep migrations transactional. Isolate non-transactional operations such as
  `CREATE INDEX CONCURRENTLY` and document cleanup/retry behavior.
- Do not use `IF EXISTS` or `IF NOT EXISTS` to conceal unexpected drift.
- Prefer SQL. A code backfill must be resumable, observable, idempotent, and
  pinned to an implementation revision.

## SQL style and schema design

- Write SQL keywords and built-in data types in uppercase. Use unquoted
  `lower_snake_case` identifiers.
- Put one major clause per line; indent continued lists, constraints,
  predicates, and references consistently. Blank lines separate concepts.
- Use plural table names and singular column names unless established domain
  vocabulary requires otherwise.
- Prefix descriptive constraints and indexes with `pk_`, `fk_`, `uq_`, `ck_`,
  `ix_`, and triggers with `trg_`.
- Every table has an explicit primary key.
- Use application-generated UUIDs for externally referenced domain identity and
  generated identity columns for internal sequences; do not introduce `SERIAL`.
- Prefer `TEXT` unless length is a real invariant, then enforce it with a named
  `CHECK` and matching domain validation.
- Use `TIMESTAMPTZ` for instants and store UTC. Distinguish event occurrence
  from observation, decision, and persistence time.
- Use `NUMERIC` for exact quantities and store currency or unit explicitly.
- Columns are `NOT NULL` by default. Every nullable column has one documented
  meaning; `NULL` must not ambiguously mean several states.
- State deletion behavior explicitly. Cascade only where the child has no
  independent lifecycle or retention requirement.
- Keep joined, constrained, filtered, and sorted fields relational. `JSONB` is
  for genuinely open or independently versioned payload content.
- Event, decision, and audit evidence is append-only unless an accepted ADR
  defines correction and retention semantics.

## Integrity, tenancy, and access

- Enforce stable invariants with primary, foreign, unique, exclusion, and check
  constraints. Application validation does not replace concurrency-safe
  database integrity.
- Include `tenant_id` or the relevant security scope in owned uniqueness and
  foreign-key paths so cross-tenant associations are impossible in storage.
- Every tenant-aware query retains its tenant predicate.
- Review privileges and row-level security when a migration creates a new data
  boundary. An application predicate is not database authorization for direct
  access.
- Never store credentials. Minimize personal and sensitive data and define
  retention, export, deletion, and audit behavior.
- When a constraint mirrors a Kotlin enum or sealed set, update both through a
  compatible sequence and add a persistence test.

## Queries, mappings, and indexes

- Add indexes only for demonstrated filter, join, ordering, uniqueness, or
  foreign-key access paths. PostgreSQL does not index referencing foreign keys
  automatically.
- Explain composite order, included columns, expression indexes, and partial
  predicates using their target query.
- Avoid speculative indexes; each adds write, storage, vacuum, and migration
  cost.
- Use set-based SQL for homogeneous bulk work rather than application loops.
- Keep database rows private to persistence adapters and reconstruct domain
  values through validated constructors.
- `DataClassRowMapper` may map stable Kotlin records when column labels,
  underscore-to-camel conversion, constructor names, and nullability are an
  exact reviewed contract. Use explicit row mapping for domain reconstruction,
  polymorphism, conversion, or compatibility logic.
- Document non-obvious locking, isolation, ordering, batching, temporal
  selection, and deliberate multi-query designs.

## Documentation inside migrations

Begin a non-trivial migration with a compact summary of its capability,
authoritative versus derived data, mutability, compatibility, write order, and
expected locking/scans. Divide substantial files into conceptual sections.

Comment decisions that are not evident from DDL: nullable meaning, intentionally
redundant constraints, lock choices, compatibility bridges, and version pins.
Add `COMMENT ON TABLE` and selective `COMMENT ON COLUMN` statements where the
meaning helps operators and analysts. Do not translate every name into prose.

## Operational safety

Before review, assess and record as applicable:

- lock level and expected duration;
- table/index size, scans, rewrites, and index-build time;
- WAL generation, replication lag, disk headroom, and vacuum impact;
- backfill volume, rate, checkpoints, resumption, and observability;
- mixed-version application behavior;
- backup and restore prerequisites;
- application rollback while the expanded schema remains; and
- forward recovery from partial failure.

Large backfills are resumable and separate from long-held schema locks. Down
migrations are not the production rollback strategy for destructive changes;
deploy compatible application code and roll the schema forward.

## Data migrations and seeds

- Seed only deterministic reference data required for correctness. Demo tenants,
  customer data, and test fixtures do not belong in production migrations.
- A transformation states source of truth, conflict handling, ordering,
  batching, restart identity, and completion verification.
- Idempotent append-only writes compare immutable content before treating a key
  collision as exact replay.
- Validate counts, constraints, checksums, or domain reconciliation before a
  contract/removal phase.

## Required verification

Every schema change must, where applicable:

- apply the complete chain to an empty supported PostgreSQL database;
- upgrade representative data from the supported released schema;
- validate Flyway checksums and ordering;
- run affected compilation, mapper, query, transaction, concurrency, and
  tenant-isolation tests;
- prove current/next application coexistence for expand/contract work;
- record manual rollout or recovery steps; and
- confirm no credential, customer data, or production evidence is present.

Unavailable checks follow the constrained-environment protocol in
[CONTRIBUTING.md](../../CONTRIBUTING.md#verification-and-constrained-environments)
and are never reported as passed.

## Primary references

- [Flyway versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)
- [PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [PostgreSQL index creation](https://www.postgresql.org/docs/current/sql-createindex.html)
- [PostgreSQL date/time types](https://www.postgresql.org/docs/current/datatype-datetime.html)
