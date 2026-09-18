# 0031. Claim human follow-up work immutably

- Milestone: M05 - Human follow-up and resolver console
- Status: proposed
- Date: 2026-09-18
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

The first authorized resolver to claim open follow-up work gains immutable,
attributed ownership. Same-resolver retries replay that claim, competing
resolvers receive a conflict, and claimed work leaves the shared inbox without
changing its `OPEN` lifecycle state.

## Context

The shared resolver inbox makes escalated work discoverable but offers no way
to prevent two resolvers from acting on the same item. Ownership must be
durable and attributable without prematurely defining reassignment, release,
completion, named queues, or a broader mutable lifecycle model.

## Decision

Let an authenticated actor with current tenant-wide `RESOLVER` evidence claim
an `OPEN` work item. Record one immutable claim containing a stable identity,
the work-item identity, resolver actor, exact authority evidence, application
claim instant, and database recording instant. A composite foreign key proves
that the evidence belongs to the recorded actor and grants resolver authority.

Serialize competing requests by locking the immutable work-item row inside the
application transaction. The first resolver creates the singleton claim. A
request by the same resolver replays it with `200`; a different resolver gets a
stable `409 human-follow-up-already-claimed`. Authorization is checked before
item lookup so an unauthorized claim attempt cannot test item existence.

Treat the shared inbox as unclaimed `OPEN` work. A claim removes the item from
that discovery view but does not change the item's lifecycle status. Keep claim
retrieval resolver-scoped and collapse absent, mismatched, and unauthorized
claim coordinates to `404`.

## Alternatives

Updating an owner column on the creation row was rejected because it would
erase ownership history and violate the row's immutable snapshot meaning.
Last-writer-wins ownership was rejected because concurrent resolvers could
silently displace each other. A lease was deferred because expiry, renewal,
clock behavior, and recovery policy are separate product contracts. Automatic
assignment was deferred until named queues and routing policy exist.

## Consequences

Resolvers can acquire exclusive ownership and safely retry the same command.
The claim is permanent in this lifecycle version. Operations must not imply
that expiry of the authority evidence later invalidates historical ownership;
it records the evidence accepted at claim time.

Claimed work remains `OPEN` and is absent from the shared inbox. A later
resolver-owned-work view, release, reassignment, and completion flow must use
new attributable events or projections rather than editing the claim.

## Compatibility and migration

The claim endpoints and table are additive. The existing inbox changes from all
`OPEN` items to unclaimed `OPEN` items; results are identical until the first
claim is recorded. The migration does not backfill ownership.

## Validation

Domain tests cover authority scope and time validity. Application tests cover
first claim, same-resolver replay, competing ownership, and pre-lookup
authorization. PostgreSQL-backed HTTP tests cover authentication, retrieval,
conflict, inbox removal, attribution, replay, and database immutability.
