# Ergon documentation

## TL;DR

Route the task through the table below, then load only the relevant detailed
guides, module README, and accepted ADRs. Use the roadmap for intended outcomes,
current state for delivered behavior, and ADRs for durable decisions. Run the
focused repository-policy check while changing the harness or documentation.

## Task routing

Always read [the contributor workflow](../CONTRIBUTING.md) and the nearest
module README. The rows below add the minimum task-specific context; follow
links from those documents only when the task crosses another boundary.

| Changed area | Read | Additional feedback |
|:--|:--|:--|
| Domain vocabulary, invariants, state transitions, or policy in `domain-kernel/` | [Product](product.md), [architecture](architecture.md), [engineering standards](development/engineering-standards.md), and the relevant ADR | Focused domain tests, then full verification |
| Application orchestration, ports, transactions, or capability code in `control-plane/` | [Architecture](architecture.md), [delivery strategy](delivery.md), [engineering standards](development/engineering-standards.md), and the capability ADRs | Focused module tests; include PostgreSQL-backed tests when persistence or transaction behavior matters |
| HTTP, authentication, authorization, browser-session, or connector adapters | [Architecture](architecture.md), [security policy](../SECURITY.md), [engineering standards](development/engineering-standards.md), and the relevant protocol or security ADR | Negative-path, tenant-isolation, authorization, cancellation, timeout, and protocol tests as applicable |
| PostgreSQL schema, queries, persistence adapters, or Flyway migrations | [SQL criteria](development/sql-migrations.md), [architecture](architecture.md), the module README, and relevant persistence ADRs | Empty-database and supported-upgrade migration evidence plus Docker-backed persistence tests |
| Resolution contracts, run policy, proof, retries, or durable compatibility | [Contracts](contracts.md), [runs](runs.md), [policy](policy.md), and the governing ADRs | Contract fixtures, failure-path tests, compatibility evidence, and full verification |
| Human follow-up or confidential BFF behavior | [Human follow-up](human-follow-up.md), [authentication](authentication.md), [architecture](architecture.md), and ADRs 0029 onward as relevant | Owner/tenant isolation, non-disclosure, CSRF/session, transaction, and browser-contract tests as applicable |
| Runtime configuration, Compose, security, or operations | The module README, [current state](development/current-state.md), [security policy](../SECURITY.md), and the relevant ADR | Render or start the affected disposable runtime and record the boundary-specific operational evidence |
| Documentation, ADRs, milestones, GitHub templates, CI, or harness tooling | [Harness guide](development/harness.md), [contributor workflow](../CONTRIBUTING.md), [ADR policy](decisions/README.md), and [ADR 0042](decisions/0042-enforce-repository-documentation-policy.md) | `./mvnw -B -ntp -pl :repository-policy verify`, then full verification |

## Product and architecture

- [Product definition](product.md) — thesis, vocabulary, users, and non-goals.
- [Architecture](architecture.md) — runtime shape, trust boundaries, and
  dependency direction.
- [Delivery strategy](delivery.md) — vertical-slice and extraction policy.
- [Resolution runs](runs.md) — current durable run and proof semantics.
- [Human follow-up](human-follow-up.md) — durable work opened from escalation.
- [Security policy](../SECURITY.md) — private reporting and change expectations;
  architecture documents the current technical trust boundaries.

## Development

- [Engineering standards](development/engineering-standards.md) — Kotlin,
  Spring, architecture, testing, and security conventions.
- [SQL migrations](development/sql-migrations.md) — schema and Flyway criteria.
- [Current state](development/current-state.md) — moving implementation and
  configuration inventory.
- [Coding harness](development/harness.md) — progressive task discovery,
  feedback tiers, enforced repository policy, and steering-loop rules.
- [Contributor workflow](../CONTRIBUTING.md) — issue, pull request, verification,
  and completion-report requirements.

## Planning and decisions

- [Milestones](roadmap/milestones.md) — outcome-oriented implementation plan.
- [ADR index](decisions/README.md) — accepted decisions and ADR policy.
- [ADR template](decisions/0000-template.md) — required decision structure.

## Module guides

Each module has a nearby README because runtime commands, configuration, status,
and replacement boundaries change at module scope. The root
[repository map](../README.md#repository-map) is the authoritative module index.

Do not duplicate moving implementation inventories in product, architecture, or
module documents. Link to current state instead.
