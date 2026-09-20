# 0035. Route human follow-up to named queues

- Status: proposed
- Date: 2026-09-19
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Persist one immutable, validated queue key when follow-up work opens. Route the
current exhausted-run path to `access-restoration` and let authorized resolvers
filter the shared inbox by queue without treating a queue as assignment or
authorization.

## Context

The tenant-wide inbox makes work discoverable but gives a resolver no stable
way to select work for a particular operational function. Inferring routing
from reason, contract, or current configuration would let historical work move
silently when those rules change. A configurable queue catalog or routing
engine is not justified while only one path creates human work.

Queue identity becomes persisted meaning used by domain, SQL, HTTP, and both
shared and owned read models. Its validation, rollout, and authorization
semantics therefore require an explicit decision.

## Decision

Add `queueKey` to the immutable work-item creation snapshot. A key is 1–63
characters, starts with a lowercase ASCII letter, and otherwise contains only
lowercase ASCII letters, digits, or hyphens. Reject invalid request filters;
do not trim, case-fold, or otherwise normalize values that could become a
different durable identity.

The exhausted access-restoration escalation path explicitly selects
`access-restoration`. No routing service or configuration abstraction is added
until a second real route requires one.

The shared inbox accepts an optional `queueKey`. With no filter it preserves
the existing tenant-wide view. With a valid key it returns only work created
for that exact queue. A valid but unknown key returns an empty page. Clients
must retain the same filter while following a cursor. Item, inbox, owned-work,
and escalation responses expose the stored key.

Queue membership does not grant resolver authority and does not identify an
owner. Existing tenant, current resolver-evidence, open-state, and unclaimed
predicates continue to govern shared discovery.

## Alternatives considered

### Derive queues during reads

- Benefits: no schema change.
- Costs and risks: old work can move when routing rules or source vocabulary
  changes; reads must reproduce historical configuration.
- Reason not selected: routing meaning must be attributable to creation time.

### Introduce a queue catalog and routing engine now

- Benefits: administrators could define queues and conditional routes.
- Costs and risks: adds unused configuration, lifecycle, and failure contracts
  before a second route exists.
- Reason not selected: the current capability needs one explicit route only.

### Make queue mandatory on the existing inbox request

- Benefits: every client chooses a narrow view.
- Costs and risks: breaks existing callers and removes the useful all-queue
  compatibility view.
- Reason not selected: an optional additive filter delivers the capability.

## Consequences

### Positive

Queue meaning is stable for existing work, every read model agrees, and a
resolver can request one operational inbox without reconstructing source
facts. The key format remains safe in URLs, logs, and indexes.

### Negative

The creation snapshot and HTTP response gain a compatibility field. Filtering
adds a second inbox access path and clients must keep filter and cursor state
together.

### Neutral or follow-up

Queue administration, display names, routing configuration, priority,
automatic assignment, reassignment, release, completion, and notifications
remain separate capabilities.

## Compatibility and migration

The schema adds a non-null `queue_key` with retained default
`access-restoration`. The constant default gives existing rows their only
historically valid route without issuing an `UPDATE` blocked by the immutable
row trigger. Retaining it also lets the preceding application version insert
during a schema-first rolling deployment. New code writes the key explicitly.

Deploy the migration before the new application; the new application cannot
read or write against the old schema. Rolling back the application is safe
against the expanded schema. The existing unfiltered inbox index remains for
old and unfiltered queries; a partial tenant-and-queue index supports filtered
oldest-first reads.

Constraint validation scans the existing table and the transactional index
build takes normal locks. Current development data is small. Production-scale
rollout must reassess lock duration and concurrent index creation.

## Security and operations

The filter is not an authorization boundary. SQL retains tenant and current
resolver-authority predicates, so queue selection cannot reveal another
tenant's work or bypass authority. Returning an empty page for an unknown valid
key avoids introducing a queue-catalog disclosure contract.

Metrics and logs may use the bounded queue key as a label only after cardinality
controls are defined; this change adds no queue-specific telemetry.

## Validation

Domain tests cover accepted and rejected key forms. Application tests cover
filter parsing and repository delegation. PostgreSQL-backed HTTP tests cover
creation, every read representation, matching and non-matching filters, and
the stable malformed-filter problem. Migration verification covers historical
backfill, the mixed-version default, and the queue-filtered inbox index. The
complete Maven reactor verifies existing unfiltered, ownership, and authority
behavior.
