# ADR 0085: Record terminal documentary review

- Status: Accepted
- Date: 2026-10-29
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M06-PR7 completes the review boundary deferred by ADRs 0082 and 0084. Reviewers
need exact cited content, explicit status, concurrency protection and attributable
decisions. Documentary acceptance must not become operational truth or execution
authority. Existing proposals and their creation responses are immutable history.

## Decision

Adopt the resolver policy proposed in ADR 0082: require current tenant-wide
RESOLVER evidence for queue, inspection, decision and exact receipt recovery.
Resolve actor identity from the authenticated workbench principal. Self-review is
permitted; this slice introduces neither a curator role nor a four-eyes rule.
Reuse existing source/citation verification for historical proposal inspection.

A typed (CLAIM or PROCEDURE) proposal starts at review version 0, PROPOSED.
A reviewer may record one terminal ACCEPTED or REJECTED decision at version 1,
selecting the exact extraction and expected version 0, with a nonblank rationale.
Preserve command ID, reviewer, authority proof and database decision time.
Rationale is trimmed, valid Unicode, at most 2000 UTF-16 units; permit tab and
line breaks but reject other controls. No content, capability or execution state
is changed. Corrections require a separately proposed immutable replacement;
replacement linking and supersession are future work.

Command IDs are unique within the tenant across proposal kinds. Exact retries by
the original actor return the original receipt, including its original proof,
after fresh authorization. Changed intent, another reviewer, stale extraction or
version, and competing commands conflict. A new current proof may authorize retry
without rewriting the original receipt. No repeated or reversible review occurs.

Serialize admission on the immutable proposal row. Recheck the selected authority
with database clock time after lock waits, including receipt recovery. SQL insert
admission also locks the target and validates the exact extraction and current
reviewer proof; database triggers prohibit updates/deletes. Only the explicit
authority failure SQLSTATE maps to unavailable; infrastructure errors propagate.

The pending queue exposes bounded metadata using keyset continuation ordered by
recording time, explicit kind order (CLAIM 0, PROCEDURE 1), and UUID, descending.
Default page size is 20, maximum 50. Cursor timestamps preserve exact PostgreSQL
microseconds. Decisions remove entries; new proposals require refreshing the
first page. No snapshot or total-count guarantee is made. Full inspection returns
verified citations and authoritative review status. Legacy proposal creation/read
responses retain their original PROPOSED envelope; consumers use the new review
inspection endpoint for current status.

## Alternatives considered

### Mutable status on proposal rows

- Benefits: simpler queue queries.
- Costs and risks: overwrites provenance and weakens immutable content storage.
- Reason not selected: keep decisions separate and append-only.

### Introduce an external workflow engine or review library

- Benefits: richer multi-step transitions.
- Costs and risks: dependency weight and permissions beyond this bounded decision.
- Reason not selected: existing PostgreSQL transactions and unique constraints
  provide the required serialization and idempotency without a new dependency.

## Consequences

### Positive

Exact review receipts survive retries and concurrent requests. Existing proposal
schemas remain compatible and no operational consumer gains authority.

### Negative

Terminal decisions cannot be edited. Queue pages can shrink during review.
Database time and application time can disagree; admission fails closed.

## Compatibility and migration

Apply additive V20261029200000 before deploying the review API. Old applications
ignore the new table and existing rows stay pending. Revert application code to
roll back exposure while retaining audit rows; do not reverse a shared migration.
Queue indexes take normal migration locks; schedule deployment for expected table
size. No data backfill or new dependency is required.

## Security and operations

Workbench session/CSRF protection and requester deny rules apply. Responses use
no-store; request bodies are bounded to 16 KiB and strict JSON rejects identity
hints, duplicate fields, unknown properties, coercions and trailing values.
Tenant predicates and typed foreign keys protect every association. Missing,
foreign and unauthorized targets return the same unavailable response. Direct
database access remains an administrative privilege, not an authentication API.

## Validation

Test exact retries with renewed proof, stale selections, conflicting commands,
both target kinds, tenant isolation, keyset ties, immutable storage, migration
upgrade/fresh chains, and authority expiry during target-lock waits. Run focused
tests, static checks, repository policy and full verification. Report Docker
unavailability as unverified PostgreSQL coverage, never a passing database gate.
