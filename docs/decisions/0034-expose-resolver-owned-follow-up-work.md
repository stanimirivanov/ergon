# 0034. Expose resolver-owned follow-up work

- Status: proposed
- Date: 2026-09-18
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Let a currently authorized resolver page through their active claimed work in
oldest-claim-first order. Keep the view actor- and tenant-scoped, return an
empty page without current resolver evidence, and preserve work and claim facts
as separate nested records.

## Context

Claiming removes a work item from the shared inbox, but the resolver can only
retrieve it again by retaining its work-item and claim identifiers. A resolver
console needs a bounded way to recover the authenticated actor's durable work
without exposing another resolver's ownership or introducing mutable
assignment, named queues, priority, or lifecycle transitions.

The claim table already has a tenant-, resolver-, claim-time-, and claim-ID
index created with immutable ownership. No schema change is needed for this
read capability.

## Decision

Expose `GET /internal/v1/tenants/{tenantId}/human-follow-ups/owned`. Return only
claimed work whose immutable resolver actor is the authenticated actor, whose
work item is `OPEN`, and for which that actor has current tenant-wide
`RESOLVER` evidence.

Order by `claimedAt` and then `claimId`, oldest first. Use bounded keyset
pagination with a `1..100` limit and an indivisible `afterClaimedAt` and
`afterClaimId` cursor. Fetch one additional row to decide whether to return a
next cursor.

Return each work item and claim as separate nested response values. Historical
claim authority proves who acquired ownership; current resolver authority
controls whether the actor may view owned work now. An authenticated actor
without current resolver evidence receives an empty page so the query does not
reveal protected work.

## Alternatives considered

### Require clients to retain claim locations

- Benefits: no new query boundary.
- Costs and risks: refresh, restart, and multi-device use lose the resolver's
  working set unless a client builds its own durable index.
- Reason not selected: ownership is server-side durable state and should be
  discoverable from that state.

### Return every tenant claim to every resolver

- Benefits: could support supervisory views.
- Costs and risks: discloses other resolvers' workloads and conflates worker
  and supervisor authority.
- Reason not selected: no supervisory authorization contract exists.

### Add owned work to the shared inbox response

- Benefits: one collection endpoint.
- Costs and risks: combines two views with different membership, ordering, and
  cursor meanings.
- Reason not selected: separate endpoints keep discovery and ownership
  semantics explicit.

## Consequences

### Positive

Resolvers can reconstruct their active working set from durable facts. Keyset
pagination stays deterministic under concurrent claims and uses the existing
ownership access path.

### Negative

The view adds a second pagination contract. Clients must retain the cursor pair
and cannot infer a stable total count.

### Neutral or follow-up

Only `OPEN` work is currently possible. Completion, release, reassignment,
queue, priority, due-time, and supervisor views require separate contracts.

## Compatibility and migration

The endpoint and response types are additive. Existing claim creation,
retrieval, and shared-inbox behavior do not change. There is no migration or
persisted-data rewrite because the required claim index and facts already
exist. Application rollback simply removes the new read endpoint.

## Security and operations

The query retains tenant, resolver actor, work status, and current authority
predicates in SQL. Another authorized resolver receives no rows for work they
do not own; an actor without current resolver authority also receives an empty
page. Invalid or untrusted authentication still fails before controller
dispatch.

The response contains existing work and authority-attribution identifiers but
introduces no new sensitive fields. The endpoint is an internal development
boundary, not a production workforce authorization model.

## Validation

Application tests cover bounded lookahead pagination and malformed cursors.
PostgreSQL-backed HTTP tests cover authentication, owner visibility,
non-owner and missing-authority empty results, stable cursor continuation, and
the error contract. The complete reactor verifies existing claim and inbox
behavior remains intact.
