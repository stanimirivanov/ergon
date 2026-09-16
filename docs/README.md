# Ergon documentation

## TL;DR

Start with the product and architecture documents. Use development guides for
implementation rules, the roadmap for intended outcomes, current state for
delivered behavior, and ADRs for durable decisions. Module-specific setup stays
beside each module.

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
