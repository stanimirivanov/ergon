# ADR 0073: Record immutable requester answers

- Status: Accepted
- Date: 2026-10-26
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR21 needs replay-safe requester responses to immutable questions. Storage,
authenticated commands and protected reads are separate reviewable backend
slices, as with PR19. Neither question possession nor an answer receipt grants access.

## Decision

PR21a establishes one immutable answer per tenant/question and a tenant-wide
response UUID. Preserve exact nonblank text, up to 4000 Unicode code points,
without NUL or unpaired surrogates. Answer text is untrusted source material,
not a bound fact, consent, approval, proof or executable instruction.

Store the question relationship, exact text, server application response instant
and independent database recording instant. Reconstruct case and requester from
immutable question/case ownership parents; never accept caller attribution.
This records the intended source identity, not proof of authentication by itself.

In a caller-owned READ COMMITTED transaction insert once, then select the whole
persisted winner after any unique-key wait. Conflicts on response UUID take
precedence over conflicts on question when both identify different rows.
Never merge a loser's text, identities or timestamps. The application must
compare every intent coordinate and authorize before acknowledging any winner.

## Alternatives considered

Editable answers would require revision, correction and replay semantics not
needed by this bounded slice. Multiple answers per question complicate which
intent was acknowledged. Copying actor/case allows contradictory attribution.
Reusing internal notes crosses privacy boundaries. Reusing the trimmed case
observation value would lose exact retry text; source interpretation remains a
separate capability, never implicit fact promotion.

## Consequences

The foundation exposes no HTTP endpoint or browser read. PR21b adds the
authenticated requester command, OPEN-case serialization and exact authorized
recovery. PR21c adds protected requester/owner projections; both consume the
same immutable storage. PR22 UI and executable guide wait for backend merges.
No case/run/ownership revision, observation stream, binding or notification changes.

One final answer cannot be corrected in place. A subsequent clarification needs
a new question. Any future correction or requester-rebinding design must
explicitly preserve historical attribution and define visibility.

## Compatibility and migration

V20261026102606 is an additive empty table with no backfill, old-row scan or
rewrite. Foreign-key creation briefly locks the question table. Existing
applications ignore it and remain compatible. Unique indexes also serve the
demonstrated winner lookups; no speculative history index is added.
Preserve schema/audit on application rollback, budget retained text, WAL and
backups before enabling a future writer. No destructive down migration.

## Security and operations

This privileged repository performs no current-access check. Integrity is not
RLS, authenticated authorship or browser authorization. PR21b must use verified
requester identity and explicit case ownership, lock against closure, and reject
changed intent without disclosing other questions. Never log or execute answers.
No new dependency, infrastructure service, or production privilege is introduced.

## Validation

Domain tests cover exact Unicode and bounds. PostgreSQL tests cover exact and
conflicting replay, concurrent first writers, both-identity collision precedence,
tenant/question integrity, whitespace parity, immutable audit, full empty-chain
migration and upgrade with retained questions. Policy and full reactor are required;
command authorization and audience-disclosure evidence belong to PR21b/c.
