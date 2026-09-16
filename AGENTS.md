# Repository working agreement

This is the concise, tool-facing entry point for contributors and coding
agents. [CONTRIBUTING.md](CONTRIBUTING.md) is the canonical workflow policy.
Normative terms have the meanings defined there.

## TL;DR

Deliver one verified vertical slice at a time. Preserve inward dependencies,
keep authority deterministic, follow the Kotlin, Spring, SQL, and documentation
standards, and report verification and remaining limitations exactly.

## Before changing anything

1. Read [CONTRIBUTING.md](CONTRIBUTING.md), especially ambiguity, issue timing,
   verification, and completion reporting.
2. Inspect the branch and working tree. Preserve pre-existing changes and do
   not mix unrelated work into the task.
3. Read the relevant module README, [engineering standards](docs/development/engineering-standards.md),
   [SQL criteria](docs/development/sql-migrations.md), and accepted ADRs.
4. Use repository-local tools and report checks exactly as run. A skipped or
   unavailable check is not a pass.

If code, documentation, and an accepted decision disagree, do not silently
pick one. Correct an obvious local error or propose a superseding ADR.

## Shape of work

- One task and pull request delivers one coherent, independently reviewable
  capability.
- Prefer a thin end-to-end slice over an unused layer or speculative framework.
- Keep unrelated refactoring, dependency updates, generated churn, and broad
  formatting out of behavioral changes.
- State scope, exclusions, compatibility effects, assumptions, risks, and
  verification evidence.
- Add no abstraction, module, service, queue, cache, datastore, or dependency
  without behavior in the current change that requires it.

## Architecture and implementation

- Organize around domain capabilities. Dependencies point inward: adapters may
  depend on application and domain code; domain code must not depend on Spring,
  HTTP, SQL, brokers, model providers, or vendors.
- Protocol parsing identifies source observations. Semantic binding attributes
  observations to asset properties. Neither layer imports the other's
  infrastructure adapters.
- Keep wire DTOs, database rows, event envelopes, model output, and domain
  values distinct when their invariants or evolution differ.
- Put interfaces at the boundary that consumes them. Do not mirror every
  concrete class with an interface.
- Model identifiers, revisions, states, risks, scopes, and units explicitly;
  make invalid states hard to construct.
- Validate untrusted protocol/model input at adapters and enforce business
  invariants in the domain.
- Keep remote calls out of database transactions. Define timeout, retry,
  idempotency, cancellation, concurrency, and partial-failure semantics.
- Treat HTTP schemas, events, configuration, migrations, persisted payloads,
  prompts, and evaluation fixtures as compatibility boundaries.

## Kotlin and Spring

- Ktlint and `.editorconfig` own mechanical formatting. Use immutable Kotlin
  values, sealed outcomes, constructor injection, and explicit nullable meaning.
- Public Kotlin APIs document purpose, invariants, parameter/return constraints,
  and meaningful errors. Prefer compiled `@sample` references when an example
  is genuinely needed; skip documentation that merely restates a name or type.
- Comments explain reasons, constraints, compatibility, concurrency, or
  security decisions; they never narrate syntax.
- Spring annotations and types stay in adapters and composition roots. Put
  transactions on application use-case boundaries and keep them short.
- Use `JdbcClient` with explicit SQL and `DataClassRowMapper` only where column
  labels and constructor semantics form a safe, reviewed mapping.

## Quality and safety

- Test observable behavior at the lowest convincing boundary. Defect fixes
  begin with a regression test.
- Tests are deterministic, isolated, parallel-safe, tenant-aware where
  applicable, and independent of wall time, network, locale, and order unless
  those are the behavior under test.
- Use Testcontainers for PostgreSQL and protocol behavior that mocks cannot
  reproduce. Docker-backed tests must actually run before being reported passed.
- Never commit secrets, customer or production data, machine-specific paths,
  hidden chain-of-thought, or unreviewed generated binaries.
- Do not expose internal endpoints as public or production-safe merely because
  they are reachable in local development.

## Completion

Run the applicable Maven Wrapper verification and follow the exact
[completion report](CONTRIBUTING.md#completion-report). Include the milestone,
copy/paste-ready issue title and body, limitations, and passed/failed/not-run
checks. Planned work is indexed in [milestones](docs/roadmap/milestones.md).
