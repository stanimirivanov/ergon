# ADR 0079: Deliver requester information notifications

- Status: Accepted
- Date: 2026-10-28
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR25b extends ADR 0078's prospective, transaction-local delivery to questions
and answers. Questions already bind an immutable case requester; answers refer to
that question but can arrive after its original author releases or transfers work.
Answer commands lock case, whereas question commands lock work then case. Adding
a work lock to answer delivery would introduce a reverse-order deadlock path.

## Decision

Add one source-keyed immutable delivery relation for questions and another for
answers, populated by AFTER INSERT triggers. No backfill, SELECT/replay writes,
generic payload, copied text/actor, application listener or dependency. Failure
to create an applicable delivery rolls back the source; do not suppress conflicts.

Question delivery references the immutable question. Its case requester binding
supplies the historical recipient without another actor column. Answer delivery
references the immutable response and captures `recipient_ownership_revision` on
that question's work. A BEFORE INSERT check validates the immutable response ->
question -> work -> CLAIMED epoch relationship, following the existing question
source-relation check pattern without redundant work/actor columns or new unique
indexes over source history. Tenant-scoped source foreign keys remain mandatory.

Answer selection reads the current ownership projection, OPEN work/case and
absence of administrative completion without taking additional locks. This is the
owner visible at the delivery SELECT, not necessarily at commit or observation.
A racing uncommitted release/handover may leave the previous epoch visible;
capture it and never retarget. No active owner means no resolver delivery, not a
rejected answer. Later claiming or replay does not generate catch-up delivery.
Authority expiry does not rewrite historical routing or grant target access.

Database delivery insertion time is separate from question/answer application
time, commit order, UI observation and read acknowledgement. Opening an alert
must reacquire current target access; notification reads remain PR25c, with PR26
UI/guide after backend review/merge. Existing commands and receipts are unchanged.

## Alternatives considered

Notifying only the original question author misses an already transferred owner.
Locking work after case risks deadlock; rewriting all command lock orders expands
this slice into a concurrency-protocol change. Snapshot selection provides bounded
historical routing without that guarantee. Current-at-commit routing, retargeting,
catch-up and external transport would require a different reviewed protocol.

## Consequences

Every new question and each eligible new answer produce at most one atomic
delivery from source uniqueness. Existing sources produce no historical alerts.
No-owner answers remain usable through normal fresh context. The immutable
recipient epoch can become obsolete before observation, so a notification cannot
promise actionable access, continuing ownership or unseen/read state.

## Compatibility and migration

The additive transactional migration creates empty tables and triggers, with
brief source-write-conflicting DDL/FK locks and no backfill or source index scan.
Compatible old/new writers automatically participate. Application rollback retains
schema, triggers and deliveries; blocking the source command routes stops new
writes if needed. Do not delete audit or remove triggers as application rollback.
No configuration, public DTO, dependency or retention contract changes.

## Security and operations

Deliveries copy no question/answer text, authority evidence, provider identity or
credentials. Source tenancy and historical recipient relationships are validated
at insertion; UPDATE, DELETE and TRUNCATE are rejected. Privileged database owners
can bypass guards, so existing restricted access, backups and immutable retention
still apply. No remote calls or mutable recipient projections are introduced.

## Validation

Compile production/test sources and run policy, formatting and static checks
locally. CI owns PostgreSQL tests for replay/concurrent deduplication, rollback,
successor selection, released/completed work, a racing uncommitted release,
immutability and tenant/epoch failures. Supported-upgrade tests preserve historical
question/answer sources without alerts on replay and verify post-cutover writes;
the independent-empty-schema gate advances to this migration. UI/guide execution
belongs to PR26; none is claimed by this backend-only slice.
