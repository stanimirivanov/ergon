# ADR 0072: Protect requester information history

- Status: Accepted
- Date: 2026-10-25
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

Immutable questions and authenticated recording exist, but neither receipt
possession nor historical authorship grants continuing read authority. Resolver
and requester audiences need different disclosure boundaries before PR20's UI.

## Decision

GET the existing work information-requests resource only as its current resolver
owner under fresh tenant-wide authority, with active work and an OPEN bound case.
Use the command's work-then-case lock order and recheck authority after waits and
history selection. Return historical receipts, including predecessor authors.

In the requester deployment, GET the case's information-requests resource using
only the verified tenant actor and immutable explicit requester binding. Bind
ownership and selected question columns in one SQL snapshot. Case closure, work
completion and resolver transfer do not erase this requester history. Return
only requestId, question, requestedAt and recordedAt; never private attribution.

Both pages contain items and truncated, select newest (recordedAt, requestId)
first and retain at most 100 rows using a 101-row probe. An owned empty case is
an empty page, not absence. Missing, unbound, foreign-owned and cross-tenant
requester cases share ADR 0069's neutral 404. Resolver absence retains ADR 0071's
neutral 404. Audience isolation, disabled-browser 503 and no-store remain intact.

## Alternatives considered

Receipt-based access would outlive ownership. Filtering already-loaded private
receipts risks disclosure and unnecessary coupling. Making requester visibility
depend on active resolver work would hide questions when resolution progresses.
Unbounded history is unsafe; pagination/discovery is deferred rather than implied.

## Consequences

Readers disclose immutable questions, not delivery, responses, evidence or consent.
No revisions advance; resolver reads briefly lock work/case rows, requester reads
do not. Older omitted questions require a future explicitly designed history API.
PR20 UI/guide and PR21 responses remain separate dependent slices.

## Compatibility and migration

New GET contracts are additive; POST receipts and existing progress are unchanged.
Deploy V20261025185000 before enabling readers. Two transactional composite index
builds scan retained questions and block writes; schedule a window and budget I/O,
WAL and index space. Preserve schema/audit on rollback and disable new GETs at
ingress or roll back application code. Existing writers remain compatible.

## Security and operations

Question text is untrusted and confidential; render as text and keep it out of
logs/model instructions. Requester ownership is immutable under current policy;
any future transfer/revocation must define historical visibility first. Direct SQL
privileges remain an operator boundary, not an RLS guarantee. No new dependency.

## Validation

PostgreSQL/HTTP tests cover bounded deterministic history, predecessor claims,
fresh authority after waits, release/closure withdrawal, requester-only fields,
empty/hidden cases, audience isolation and migrations. Existing security and
staged harness checks remain required; skipped Docker suites are not passes.
