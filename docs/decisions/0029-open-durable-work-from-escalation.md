# 0029. Open durable work from escalation

- Milestone: M05 - Human follow-up and resolver console
- Status: proposed
- Date: 2026-09-16
- Deciders: Ergon maintainers
- Supersedes: none

## Context

`ESCALATION_REQUESTED` proves that a resolver explicitly ended automated
recovery, but an event alone is not an addressable unit of human work. A
resolver experience needs a durable identity and source contract before queue,
assignment, priority, notification, or UI behavior can be defined.

Existing escalations must gain the same invariant during schema upgrade; replay
cannot depend on whether an event was recorded before or after this capability.

## Decision

Atomically create exactly one `OPEN` human follow-up work item when recording an
exhausted-run escalation. The immutable creation snapshot identifies its case,
run, source escalation event, reason, opening time, and database recording time.
The source event and work item are tenant-scoped and foreign-key bound.

Backfill one item for every existing `ESCALATION_REQUESTED` event during
migration. Escalation replay returns the existing item without allocating a new
identity. A `201` escalation response locates the work item; replay returns the
same location with `200`.

Expose tenant-scoped retrieval by work-item ID. The caller must be an
authenticated actor with current tenant-wide resolver evidence. Return the same
not-found contract for absence and insufficient resolver authority so lookup
cannot disclose hidden work.

## Alternatives

Treating escalation events directly as queue items was rejected because it
would couple resolver lifecycle and addressing to the resolution-run event
schema. Creating work lazily on first read was rejected because escalation
could remain unactionable indefinitely. Adding assignment, priority, or
notification now was rejected because none has a reviewed ownership or delivery
contract yet.

## Consequences

Every escalated run has one retrievable unit of human work, including upgraded
history. The creation snapshot remains immutable; future lifecycle behavior
must append attributable transitions and may maintain a separate projection.
The case remains open and no owner, queue, priority, due time, notification, or
new capability authority is implied.

The migration adds and validates a composite source constraint, creates and
backfills the work-item table, and installs an immutability trigger. Backfill
uses PostgreSQL-generated UUIDs and may write one row per existing escalation.

## Compatibility and migration

The escalation response gains `followUpWorkItemId` and `followUpStatus` fields.
Its `Location` changes from the run resource to the newly created work item,
which is the resource represented by `201 Created`. Consumers relying on the
old internal location must use the response `runId` for run retrieval.

## Security and operations

Bearer authentication and current resolver evidence gate retrieval. Tenant,
actor, and authority predicates are evaluated in the persistence query to avoid
cross-tenant or unauthorized existence disclosure. Work items currently contain
identifiers and workflow facts, but later summaries must review sensitive-data
and retention requirements separately.

## Validation

Domain tests cover creation and rehydration. Application tests cover atomic
creation inputs. PostgreSQL-backed HTTP tests cover first creation, replay,
authenticated resolver retrieval, denial without resolver evidence, source
uniqueness, immutability, and the open case. Flyway tests apply the complete
migration chain from an empty database and the existing supported upgrade
fixture.
