# ADR 0067: Record independent follow-up priority

- Status: Accepted
- Date: 2026-10-24
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR13 adds revisioned administrative priority without changing the existing
oldest-first queue/cursor contracts. Priority needs its own immutable history:
ownership fences, runtime risk, proof and case revisions mean different things.
Deliver the shared schema/integrity foundation first (PR13a), then the authenticated
command/recovery (PR13b) and active read projections (PR13c). Both later slices
consume this foundation; PR14 UI/user guide waits for all three.

## Decision

Append tenant/work-scoped priority assignments with LOW, NORMAL, HIGH or URGENT.
No assignment means NORMAL at independent priority revision zero. Each accepted
set advances that revision by one, even when repeating a value with a new command.
An original command identity is unique within tenant/work. Exact replay belongs
to the later application command, not blind duplicate SQL insertion.

The insert guard locks the immutable work row before separately reading terminal,
current-ownership and latest-priority state. Require exact active claim, actor and
ownership revision, plus the next contiguous priority revision. Tenant-safe foreign
keys retain the CLAIMED attribution and accepted RESOLVER evidence. Reuse the
append-only mutation guard. The primary key also supports latest-priority lookup;
add no mutable projection or speculative indexes.

Priority does not affect queue filtering/order, ownership, risk, authority, runtime
eligibility, case/run state or verified proof. No public producer, API field,
application port or feature flag is introduced in this foundation.

The later command must enforce session/CSRF and current tenant-wide resolver
authority after waiting, including on replay. Recovery requires the original actor
and original exact active ownership, not merely possession of a historical command.
It must compare complete intent, fail closed on terminal work and return sanitized
receipts. SQL attribution is neither authentication nor authority freshness.

## Alternatives considered

Changing queue sort breaks stable keysets. Reusing ownership revisions conflates
metadata and access fences. Updating opening rows loses history. A mutable
priority projection requires another synchronization boundary without a demonstrated
need; indexed history is sufficient. A single oversized schema/command/read PR
would obscure independent persistence and disclosure review.

## Consequences

Independent revisions make concurrent metadata updates explicit without fabricating
ownership transitions. Priority history survives handover and completion, while
new assignments after completion are rejected. All writers share work-row lock
order and use short READ COMMITTED transactions; no remote I/O is permitted.
No SLA, due dates, override, requester or notification semantics are implied.

## Compatibility and migration

V20261024101559 adds an empty table/functions/triggers, with no backfill, old-row
rewrite or existing-table index build. Referenced-table DDL locks still require a
deployment window. Existing binaries and payloads are unchanged; never infer
priority-aware display before PR13c. PR13b stays disabled until compatible readers
and UI are deployed. Rollback disables its producer and retains schema/audit and
existing terminal guards; it never deletes priority or completion facts.

## Security and operations

The database rejects mismatched attribution, stale ownership, skipped revisions,
duplicate intent and audit mutation. Privileged SQL remains outside HTTP
authentication. No provider subject, token or customer text is stored. The later
HTTP boundary must independently authorize access and avoid exposing hidden work.

## Validation

Real PostgreSQL tests cover empty/previous-schema migration, unchanged upstream
snapshots, all values, revision/intent/tenant/claim/authority integrity, rollback,
immutability and database-observed competing/terminal lock waits. Run repository
policy and full Maven verification; skipped Docker tests are not passes.
