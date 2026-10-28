# ADR 0078: Deliver atomic handover notifications

- Status: Accepted
- Date: 2026-10-28
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR25 needs durable, deduplicated in-app delivery without granting target
access. Existing handover receipts already pin one recipient to one authorized
transfer and exact replay never inserts another receipt. The first slice must
work for mixed-version writers without an asynchronous delivery gap.

## Decision

An AFTER INSERT trigger on `human_follow_up_handovers` creates one immutable
`human_follow_up_handover_notifications` row in the same transaction. Its primary
key is the exact tenant/work/command source tuple; a composite foreign key pins
the recipient to that source. No generated delivery UUID or arbitrary JSON
payload is needed. `delivered_at` is database insertion time, not occurrence,
client observation or read acknowledgement. It has no cross-clock ordering claim.

Start prospectively when the migration commits. Do not backfill historical
transfers or emit on SELECT/replay. Failure to insert the delivery fails the
handover transaction, including ownership. Do not suppress conflicts. All source
writers use the existing receipt contract; no application event bus, listener,
new application abstraction, dependency or background worker is introduced.

Keep deliveries in the follow-up capability, beside their source. This narrow
relation is a current consumed projection, not a generic notification framework.
Later PR25 slices add question/answer delivery and protected bounded reads;
PR26 composes browser notification surfaces and their guide after backend merge.
Do not export database rows as a browser contract.

## Alternatives considered

An application callback requires every old writer to be upgraded before atomic
delivery is dependable. An asynchronous listener can lose committed work; an
outbox/broker adds a delivery protocol without remote transport. Reading all
historical handovers as alerts cannot distinguish the adoption boundary. A generic
payload table weakens source/recipient integrity before variation justifies it.

## Consequences

New handovers immediately produce durable delivery, even before browser listing
exists. Exact and concurrent replay reuse one source and therefore one delivery.
Delivery remains historical after release, further transfer or closure. No mutable
unread state or retention change is implied. A future read must authenticate the
recipient; opening a target must independently recheck current target access.
Internal case/note/question text and authority evidence are not copied.

## Compatibility and migration

Apply the additive migration before compatible new application deployment. Existing
handover writers remain compatible and the existing handover enablement flag still
controls commands. The source unique index requires an ACCESS EXCLUSIVE lock and
a source scan; assess row count, write traffic, disk/WAL and the maintenance window.
The transaction installs the relation and trigger together, with no backfill.

Reverting application code does not stop database delivery. Disable handover writes
with the existing flag if necessary and recover forward; do not remove the trigger
or delete deliveries as an application rollback. Old receipts and their original
recovery behavior remain unchanged. No browser contract is exposed by this slice.

## Security and operations

Recipient/tenant references are database-enforced and UPDATE, DELETE and TRUNCATE
are rejected. As elsewhere, privileged owners can bypass triggers; this is not
database authorization against administrators. Use the existing restricted
database access, backups and immutable evidence retention. No email, push, webhook,
customer payload or provider identity is added. Existing handover transactions
incur one local insert and its index maintenance; no remote call is introduced.

## Validation

Compile production/test sources, formatting, static analysis and repository policy
locally. CI must run PostgreSQL tests for authenticated creation, exact/concurrent
replay, rollback, immutable history after release, tenant/recipient FK failures,
empty migration and supported upgrade without historical alerts. Tests isolate
only the old source graph when copying an authorized pre-upgrade receipt; new
notification constraints/triggers are never disabled. Browser/UI/guide checks
belong to the later protected-read and PR26 deliveries, not this storage slice.
