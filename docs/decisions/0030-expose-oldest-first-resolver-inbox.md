# 0030. Expose an oldest-first resolver inbox

- Milestone: M05 - Human follow-up and resolver console
- Status: proposed
- Date: 2026-09-18
- Deciders: Ergon maintainers
- Supersedes: none

## Context

Escalation creates addressable human follow-up work, but a resolver must already
know a work-item ID to retrieve it. The first discovery boundary must remain
useful when work grows without prematurely defining organizational queues,
assignment, ownership, priority, or mutable lifecycle storage.

## Decision

Expose a tenant-wide inbox of `OPEN` human follow-up work to authenticated
actors with current tenant-wide resolver evidence. Order work by `openedAt`
ascending and break ties by work-item ID so older work remains visible first
and pagination is deterministic.

Use bounded keyset pagination. A request accepts `limit` from 1 through 100 and
an optional pair of `afterOpenedAt` and `afterWorkItemId` values returned as the
previous page's `nextCursor`. Fetch one extra row to determine whether another
page exists. An incomplete cursor or invalid limit returns a stable `400`
problem response.

Keep resolver evidence inside the persistence query. A caller without current
resolver evidence receives an empty page, indistinguishable from an authorized
empty inbox, so listing cannot reveal whether tenant work exists. Add a partial
index over tenant, opening time, and work-item identity for `OPEN` rows.

## Alternatives

Offset pagination was rejected because later lifecycle transitions could shift
rows between pages. Returning every item was rejected because resolver work is
unbounded. Introducing named queues or a configurable routing policy was
rejected because no ownership or routing contract exists yet. Returning `403`
for missing resolver evidence was rejected because an empty result reveals less
tenant state while still returning no protected work.

## Consequences

Resolvers can discover work without transcript reconstruction or prior IDs.
The inbox is a read model, not a durable queue assignment: it implies no owner,
priority, reservation, or entitlement beyond the current request. Clients must
treat the cursor fields as an indivisible continuation position and must not
infer a stable total count.

The partial index is built transactionally. Current development data is small;
production-scale rollout must reassess lock duration and whether concurrent
index creation warrants a separate non-transactional migration.

## Compatibility and migration

The collection `GET` is additive. Existing item retrieval and escalation
responses do not change. The migration adds only an index and is safe for the
current and preceding application versions.

## Validation

Application tests cover bounded lookahead pagination and invalid input.
PostgreSQL-backed HTTP tests cover authentication, authorized listing, absent
authority, and the stable error contract. Migration verification covers the
supported historical backfill and the inbox index.
