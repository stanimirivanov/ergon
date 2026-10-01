# ADR 0043: Define human follow-up release semantics

- Status: Proposed
- Date: 2026-10-01
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

[ADR 0031](0031-claim-human-follow-up-work.md) gives one resolver an immutable,
singleton claim per work item. The shared inbox excludes any item with a claim,
and the owned view treats that claim as current ownership. A release endpoint
alone would therefore strand the item: it could neither return to the inbox
nor be claimed again. The initial work-item snapshot and first claim are audit
facts and must not be rewritten to simulate a release.

Release must be distinguishable from reassignment and completion. It changes
who may act on still-`OPEN` follow-up work, not the case outcome, escalated run,
initial queue, or historical attribution. A retry of a release must not release
a later owner's claim. Existing browser and internal claim commands have no
client command identity, so their same-resolver replay contract cannot safely
represent a second claim cycle after release.

## Decision

Propose an append-only ownership history with a transactionally maintained
current-ownership projection. Retain the immutable creation snapshot and first
claim. A release records the releasing claim, authenticated resolver, authority
evidence accepted for the command, occurrence and recording instants, and a
monotonic work-item ownership revision. It clears only the current owner. The
item stays `OPEN` and returns to its original named queue. It does not change
the case or run, imply resolution, or send a notification in this slice.

Only the current owner with current tenant-wide `RESOLVER` authority may first
release. The command names the exact active claim and expected ownership
revision. Serialize claim and release against the same tenant-scoped work-item
lock; append the event and update the projection in one transaction. A stale
claim or revision cannot release a successor owner. A retry by the same actor
for an already recorded release returns that release receipt without a second
transition; a different actor does not gain its receipt through replay.

Each later claim receives a new claim identity and current authority
attribution. Its command requires a stable client command identity and expected
ownership revision so an ambiguous retry cannot become another claim cycle.
The existing claim command keeps its first-claim behavior; it must never create
or pretend to own a later claim. Define additive internal and CSRF-protected
browser command contracts with independent DTOs in the implementation slice.
The browser never supplies an actor ID or authority evidence ID.

The current projection, not the historical first-claim table, determines shared
inbox and owned-work membership after release is enabled. Historical claim
retrieval remains an audit read, not proof of current ownership. Case-summary
access continues to require both current ownership and current resolver
authority; absent, released, and differently owned work remain
non-disclosing.

## Alternatives considered

### Clear or replace the first claim

- Benefits: fewer tables and joins.
- Costs and risks: destroys historical attribution and changes the meaning of
  an existing claim identity and its replay response.
- Reason not selected: immutable claim evidence is a compatibility boundary.

### Add only a release event and infer current ownership in every query

- Benefits: one append-only table without a projection.
- Costs and risks: every inbox, owned-work, claim, and case-summary query must
  independently reconstruct the latest event under concurrency.
- Reason not selected: a transactionally updated projection gives one indexed,
  testable current-state predicate while events retain the audit source.

### Reuse the existing claim POST for every cycle

- Benefits: no new client command shape.
- Costs and risks: an old retry after release is indistinguishable from a new
  claim intent and can unexpectedly reacquire work.
- Reason not selected: multi-cycle claims need explicit command identity and
  expected-state concurrency.

## Consequences

### Positive

Release can safely return work to discovery while preserving the first claim
and every later ownership transition. Exact-claim and revision checks prevent
an old release retry from affecting a new owner.

### Negative

Ownership gains an event stream, a current-state projection, a new claim
command contract, and a mixed-version rollout constraint. Historical claim
retrieval must be described separately from active ownership.

### Neutral or follow-up

Supervisor recovery for an owner who loses authority, reassignment, priority,
completion, service levels, and notification delivery remain separate
decisions. This proposed ADR does not authorize an implementation to infer
those policies.

## Compatibility and migration

Use expand, backfill, verify, then enable. Add history and projection storage
without altering immutable rows; backfill current ownership from existing
first claims and verify a one-to-one match before enabling commands. Migrate
all inbox, owned-work, claim, and case-summary reads to the projection. Do not
enable release while old application instances can serve ownership reads or
claims. The existing singleton claim constraint cannot simply be dropped in a
mixed-version deployment; later claims need a separate compatible write path.

Before the first release event, rollback may disable the new commands. After a
release or later claim is durable, old binaries cannot safely interpret
current ownership; use forward recovery, not a downgrade that ignores the
history. Exact migration DDL and browser paths belong with the implementing
slice and require empty-database and supported-upgrade verification.

## Security and operations

Tenant scope is part of every key, lock, event, projection, and query. Resolve
the actor from verified authentication; check current resolver evidence before
the initial release or claim, and retain the accepted evidence identity only in
server-side audit records. Browser mutations require the existing session CSRF
boundary and no-store responses. A stale revision produces a conflict without
exposing another owner's identity. Non-owners and callers without current
authority must not use release or current-state reads to enumerate work.

Observe mismatches between event history and the projection as invariant
failures, not as permission to guess an owner. No remote call occurs inside an
ownership transaction. Release and later-claim rates, conflicts, and
projection-repair attempts need tenant-safe operational metrics without case
content or provider identity.

## Validation

Before implementation, maintainers must review the owner-only release rule,
the treatment of historical claim reads, and the new multi-cycle claim command
shape. Implementation tests must cover first claim, release, same-actor replay,
stale release after another claim, concurrent claims, authority expiry,
cross-tenant and cross-owner non-disclosure, browser CSRF, and projection
consistency. PostgreSQL tests must prove migration backfill and supported
upgrade; rollout evidence must show no old instance serves ownership traffic
after release is enabled.
