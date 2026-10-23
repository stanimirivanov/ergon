# ADR 0065: Retire administratively completed follow-up work

- Status: Accepted
- Date: 2026-10-23
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR11 needs administrative human-work completion without claiming verified
case resolution. The OPEN work row is an immutable opening snapshot, not a
mutable lifecycle projection. Every active-work reader and writer must understand
terminal work before any completion producer is enabled. Split delivery into
PR11a schema/guards and PR11b authenticated command to preserve reviewable scope.
The same active-work boundary will guard PR13 priority changes.

## Decision

Record one immutable `human_follow_up_completions` fact per tenant/work item,
with original command identity, exact CLAIMED event/revision, actor, accepted
resolver authority and separate completion/recording instants. Tenant-safe foreign
keys and a work-row-locking insert trigger require exact current ownership.
This is neither a RELEASED event nor an update of opening/ownership history.
Keep the final ownership projection as historical coordinates; it confers no
active access when a completion exists. No case, run, evidence or proof changes.

All active discovery, current work/claim reads and ownership operations exclude
completed work. Preserve existing neutral absence responses. Source lookup by
escalation deliberately retains the immutable opening snapshot for historical
escalation replay; it is not an active-work access gate.

Writers use the existing work-row lock in a short READ COMMITTED application
transaction, then check completion in a separate statement. A locking SELECT's
pre-wait snapshot is insufficient when the locked row remains unchanged.
Notes/context gates also retire completed work. Read observations are not
continuous authority or a cross-request atomic snapshot.

PR11a exposes no completion HTTP write or enablement flag. PR11b must resolve the
session actor, enforce CSRF, check current resolver authority after the lock wait,
validate exact ownership and original intent, and gate historical recovery
separately from private active access. SQL attribution is not authentication or
an authority freshness check. The dependent UI/guide waits for that command.

## Alternatives considered

Updating OPEN creation rows destroys the existing immutable source contract.
Releasing work fabricates shared-queue intent. Reusing accepted outcome proof
misstates administrative completion as verified resolution. Checking completion
only before a lock wait permits stale active decisions after terminal commit.

## Consequences

Terminal work disappears without erasing source, claims or commentary. Guard
queries add indexed tenant/work lookups. Completion, claim, release, notes and
handover serialize on one work row; no remote I/O belongs in that transaction.
Reopening, overrides, notifications and a completed-work directory are excluded.

## Compatibility and migration

Deploy additive V20261023195734 and all guarded readers/writers before PR11b can
produce facts. No existing rows or indexes are rewritten. Referenced-table DDL
locks still need a bounded deployment window. Rollback before first use can leave
the empty schema; after first use retain compatible guards and immutable audit,
disable new completion writes and recover forward. Never downgrade to readers
that interpret the opening snapshot alone as active work.

## Security and operations

Direct SQL credentials remain privileged; foreign keys and insert guards are
integrity, not a public authorization boundary. Retain audit under restricted
operational access and backups. No provider identity, token or customer text is
added. A historical completion fact never grants case/notes or execution access.

## Validation

Real PostgreSQL tests exercise empty/previous-schema upgrade, attribution,
immutability, rollback, active read/write withdrawal and a database-observed lock
wait. Source/case/run/proof snapshots remain unchanged. Repository policy and
full Maven verification are required; skipped Docker tests are not passes.
