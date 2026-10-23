# ADR 0063: Record owner-attributed internal follow-up notes

- Status: Accepted
- Date: 2026-10-23
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

Resolvers need attributable commentary that survives release and later claiming.
It must not become evidence, runtime instructions, consent or outcome proof.
The approved M05-PR7 scope adds the backend before M05-PR8's UI and guide.

## Decision

Expose GET and POST at
`/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/notes`.
Resolve the actor from the verified session; POST requires session-bound CSRF.
In one short application transaction, lock the open work item using the existing
claim/release lock, then evaluate current tenant-wide resolver authority and
ownership at the post-lock instant. A failed gate is one non-disclosing 404.
POST additionally matches the exact claim and ownership revision, or returns 409.

Store append-only plain text, 1–4000 Unicode code points, nonblank and without
NUL/unpaired surrogates. Preserve exact text, including whitespace. The client
note UUID is an intent identity within tenant/work item: exact authorized replay
returns the original receipt (200 rather than first-write 201); changed text,
author or ownership coordinates conflict. Replays still require current ownership.
Notes neither advance ownership revision nor change case/run/evidence/proof state.

Reference the immutable CLAIMED event through a tenant-safe foreign key. Derive
author and claim from that event instead of persisting contradictory copies.
Browser records expose only internal actor UUID, claim/revision, note identity,
text, application authoring instant and database recording instant, never provider
subject, credentials or authority evidence. A successor authorized owner can read
predecessor notes, not impersonate their writes. GET returns the latest 100 notes
in descending (recordedAt, noteId) order with explicit older-history truncation.
This is a bounded working view, not a complete export or requester disclosure.

## Alternatives considered

Case observations would falsely promote commentary into evidence. Mutable notes
would lose attribution and exact retry meaning. Unlocked reads/writes could race
release; a note table with copied actor/claim identifiers could contradict the
ownership history. A general conversation framework adds no needed behavior.
Older-history pagination is deferred rather than silently implying completeness.

## Consequences

Internal commentary is durable and attributable without changing existing
ownership or runtime semantics. Current-owner locking briefly serializes reads,
appends and release on one work item; it does not lock a whole queue. There are
no remote calls, automatic mutation retries, new dependencies or notification writes.

## Compatibility and migration

The additive migration adds notes, an immutable-row guard and an index serving
newest-first reads. A redundant ownership-history unique key permits an FK that
also fixes event kind to CLAIMED; its build scans history under an exclusive
ALTER TABLE lock. Assess history size/lock budget before deployment. No backfill.
Old binaries ignore notes safely; rollback retains schema and notes, never deletes
audit. Backend merge/deployment precedes UI/guide. Browser-disabled mode stays 503.

## Security and operations

Notes are untrusted internal text: render inertly, never execute or send to
requesters/models as trusted instructions. Current access is application-enforced,
not a promise of database row-level security for direct SQL credentials. No
content logging, search, edit/delete or general retention/export API is added.
Restricted operational access and existing backup retention apply; any erasure
policy must explicitly reconcile immutable audit rather than bypass triggers.

## Validation

Domain Unicode tests and real PostgreSQL/BFF tests cover attribution, exact and
competing retry, current-owner/authority loss, release races, bounded ordering,
tenant constraints, mutation guards, empty startup and previous-schema upgrade.
Repository policy and complete Maven verification remain required.
