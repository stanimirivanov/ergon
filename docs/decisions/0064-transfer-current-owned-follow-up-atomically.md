# ADR 0064: Transfer current-owned follow-up atomically

- Status: Accepted
- Date: 2026-10-23
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR9 needs direct current-owner handover before the dependent browser UI.
Release followed by a separate claim exposes work to competitors and cannot
preserve exact transfer intent. Immutable first claims and predecessor notes
must retain their attribution. The approved policy permits only the current
authorized owner to initiate a first transfer, not supervisor override.

## Decision

Use POST `/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/handovers`
with a client command UUID, original claim UUID, expected ownership revision
and proposed recipient internal actor UUID. Resolve the sender from the OIDC
session and require existing session CSRF/no-store behavior. Self-transfer is
invalid. There is no identity directory or provider-subject disclosure.

In one short application transaction, lock the OPEN work item using the existing
claim/release/note lock. Evaluate sender authority after that wait. For a new
intent, require exact current ownership/claim/revision and current tenant-wide
recipient resolver authority at that same decision instant. Append the recipient's
immutable CLAIMED event with its accepted authority, replace current ownership
directly and persist the attributed command receipt. No remote call occurs.

Advance handover revision by two, preserving claimed/released parity understood
by existing browser clients. Revisions fence ownership; they are not event
counts. The unused intermediate value is not a RELEASED event or unowned state.
Existing projection-based readers and notes attribution remain unchanged.

An exact replay by the original sender with current resolver authority returns
only the original receipt, even after ownership changes or recipient authority
expires. It does not disclose successor-private context or reapply the transfer.
Another sender cannot recover the receipt; same-sender changed intent conflicts.
Missing/closed work, another owner and lost sender authority share one 404.
Every ineligible recipient (absent, foreign tenant, expired or insufficient
authority) shares a single rejection after the owner gate.

## Alternatives considered

Separate release and claim has a committed race window. An atomic release/claim
pair avoids that window but invents release intent and lets old release replay
misidentify a handover as its own command. Rewriting the first claim destroys
audit. A new ownership-event kind would force all claim readers and notes
foreign keys to change for no additional current behavior.

## Consequences

Ownership moves atomically with deterministic replay, without granting runtime
supervision, lease, capability authority, proof or case completion. The former
owner loses notes/case-context access and the recipient inherits existing notes
with original authorship. Historical acknowledgement has deliberately weaker
access requirements than private notes, because otherwise the sender could not
recover an uncertain successful transfer. Closed-work acknowledgement remains
unavailable; administrative completion must retain that documented boundary.

## Compatibility and migration

Deploy additive V20261023180000 before backend code. The supporting unique key
scans ownership history under an exclusive ALTER TABLE lock; assess table size,
index/WAL space and maintenance window. No backfill or event rewrite. Deploy all
projection-aware ownership readers/writers and the dependent UI before enabling
`ERGON_HUMAN_FOLLOW_UP_HANDOVER_ENABLED=true` (default false). Release need not be
enabled. Disable new handovers for rollback, retain schema/receipts and recover
forward rather than downgrade to original-first-claim readers.

## Security and operations

Tenant-safe foreign keys bind both exact CLAIMED events and the sender's accepted
authority to the receipt. Current access remains application-enforced, not SQL
row-level security for direct database credentials. Do not log sensitive context
or expose authority evidence/provider identities. Retain immutable audit under
existing restricted operational access and backup policies. No notifications,
recipient search, automatic retry, administrative override or lifecycle change.

## Validation

Real PostgreSQL/BFF tests cover atomic visibility and rollback, exact/changed
replay, stale state, competing writes, tenant/actor isolation, authority expiry,
private access retirement, inherited notes, CSRF/no-store and disabled behavior.
Empty schema and previous-schema upgrade verify Flyway and persisted ownership.
Repository policy, Kotlin analysis and complete Maven verification are required.
