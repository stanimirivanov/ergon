# ADR 0070: Record requester information requests

- Status: Accepted
- Date: 2026-10-25
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05 needs attributable questions for an explicitly bound case requester. Internal
notes have a different privacy boundary; requester ownership is not approval
authority. The approved three-part split separates storage, command and reads.

## Decision

PR19a establishes immutable question values and tenant-scoped persistence. A
question preserves exact text, is nonblank under Kotlin whitespace semantics and
has at most 4000 Unicode code points, without NUL or unpaired surrogates.

A request UUID identifies intent across work items within a tenant. Insert once,
then return the persisted winner; never merge competing text or timestamps.
The repository requires a READ COMMITTED transaction and reconstructs author and
claim from the exact immutable CLAIMED event on both first write and replay.
It performs no current-access check or intent-conflict decision.

Tenant-safe foreign keys require the historical ownership event and explicit
case requester binding. A trigger verifies the case matches the immutable
work/run source. Attribution is derived rather than redundantly persisted.
Questions are untrusted text, not observations, consent, approval or proof.

## Alternatives considered

Reusing notes blurs confidentiality. Copying recipient/author identities permits
contradictory attribution. A generic conversation framework is unnecessary.
Shipping the command and reads together exceeds the agreed review-size boundary.

## Consequences

This foundation has no HTTP entry point. PR19b adds authenticated creation and
exact authorized recovery; PR19c adds protected owner/requester projections.
Both consume this storage contract; PR20 UI/guide waits for both to merge.
No delivery, response, runtime action or case/ownership transition is introduced.

The command must check current authority and ownership, exact claim/revision,
an OPEN explicitly bound case, and immutable intent before returning a receipt.
Those checks are not provided by this repository or by historical foreign keys.

## Compatibility and migration

V20261025141955 creates an empty table, without backfill, old-row scans or rewrite.
Foreign-key DDL briefly locks parents; existing writers ignore the new table.
Retain schema and audit rows on application rollback. No destructive down
migration is supported. Read indexes belong with PR19c's demonstrated queries.
Budget retained rows, WAL, indexes and backups before enabling the future writer.

## Security and operations

No new browser authority or disclosure exists in PR19a. Database credentials are
privileged; integrity constraints are not RLS or application authorization.
Never log question text or execute it as HTML/model instructions. Future requester
reassignment must define historical visibility before changing immutable bindings.

## Validation

Domain tests cover exact Unicode and bounds. Real PostgreSQL tests cover winning
write/replay, concurrent intent, derived attribution, tenant/source integrity,
SQL whitespace parity, mutation rejection, empty-chain startup and upgrade from
the preceding released schema. Repository policy and full reactor are required.
Authenticated command and protected-read tests belong to PR19b and PR19c.
