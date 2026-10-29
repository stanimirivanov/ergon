# ADR 0086: Adjudicate bounded documentary contradictions

- Status: Accepted
- Date: 2026-10-29
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M06-PR8 adds the bounded assessment and human adjudication deferred by ADR 0083.
Accepted documentation can disagree without either assertion becoming operational
truth. A reviewer must see exact accepted candidates, verified quotations and
explicit uncertainty. Concurrent acceptance must not let stale candidate sets
authorize a decision about different evidence.

## Decision

Use pinned algorithm documentary-contradictions-v1 over accepted CLAIM proposals.
Applicability is the existing exact property/subject/scope key; labels retain their
case-sensitive meaning. Different labels are NOT_COMPARABLE, not proof that their
real-world applicability is disjoint. There is no unknown-scope sentinel or ontology
in the existing model, and this slice introduces neither. No cross-scope consistency
claim is made.

For matching labels, absent validity on either claim yields UNDETERMINED, even when
values match. Known half-open intervals that do not overlap yield DISJOINT_VALIDITY.
Equal values with known overlap are COMPATIBLE. Opposing SUPPORT booleans with
known overlap are CONFLICT. Differing free-text REQUIREMENT values remain
UNDETERMINED: two instructions may coexist, so text inequality cannot establish
logical incompatibility. No NLP, model inference or automatic winner is introduced.

An assessment selects the complete accepted set for an accepted anchor claim's key.
Sort candidate UUIDs by canonical UUID order and compare every pair (i,j), i < j.
Bound the set to 20 candidates (190 pairs); reject larger sets without truncation.
Expose source/extraction/compiler, accepted review identities, exact verified
citations, set revision and findings. A single candidate is insufficient for
adjudication. NO_DETECTED_CONFLICT is a bounded result, never general consistency.

Each accepted claim advances its key's mutable concurrency revision in the same
transaction as the immutable review. Backfill existing accepted groups before
enabling the trigger while locking review writes. Rejected claims, pending claims,
procedures and exact review retries do not advance this revision.

A resolver explicitly records PREFER_CLAIM with one candidate or DEFER with no
selection, a rationale, exact ordered candidate IDs and expected set revision.
Require a CONFLICT or UNDETERMINED finding; compatible-only sets need no decision.
One immutable adjudication exists per exact group/revision, irrespective of anchor.
Preference does not edit, reject, suppress or supersede another accepted claim;
undetermined comparisons remain visible and unresolved by the algorithm.

Unadjudicated assessments are read snapshots. An adjudication freezes its candidate
and accepted-review IDs, pair codes, algorithm, intent, reviewer, authority proof
and database time. Exact same-actor retries recover that history after fresh
authorization, even after later acceptance. Changed intent or a second decision
for the same snapshot conflicts. Historical reads verify original citations and
recompute pinned v1 codes as an integrity check; future algorithms must retain v1
reading rather than silently reinterpret saved decisions.

Serialize new decisions and acceptance on the exact claim-key row. Admission
rechecks candidate/review identities and revision, and current resolver authority
with database time after lock waits. SQL triggers repeat admission checks for
direct producers and prohibit UPDATE/DELETE. A concurrent acceptance either
precedes adjudication (stale inputs conflict) or follows it (the saved decision
becomes historical). The current flag is a read snapshot, not a continuing promise.
Explicit authority/stale SQLSTATEs map to unavailable/conflict; infrastructure
errors propagate.

## Alternatives considered

### Infer contradiction from unequal strings

- Benefits: more apparent coverage.
- Costs and risks: compatible requirements become false contradictions.
- Reason not selected: only typed boolean opposition proves incompatibility here.

### Add a workflow or temporal reasoning dependency

- Benefits: broader temporal/semantic expressiveness.
- Costs and risks: dependency weight, licensing review and unneeded execution
  surface for two existing property types.
- Reason not selected: adapt existing PostgreSQL locking/idempotent receipt
  patterns and implement the small half-open interval predicate in the domain.
  No borrowed implementation, new dependency or wrapper is required.

## Consequences

### Positive

Review inputs remain exact under concurrent acceptance. Original claims and
findings remain attributable; preference grants no execution authority.

### Negative

Large groups require later product work; there is no partial assessment.
Preference is terminal for one snapshot. No in-place correction, undo, multi-winner
selection or automatic conflict resolution is available.

## Compatibility and migration

Apply additive V20261029210000 before the API. The migration briefly blocks review
writes while installing/backfilling the acceptance projection and adds a group
lookup index. Plan migration time for existing proposal volume. Old applications
continue using the same review schema; database triggers advance the projection.
Rollback application code while retaining immutable history; do not reverse a shared
migration. Bundles and workbench adjudication UI remain later M06 slices.

## Security and operations

Reuse workbench OIDC/CSRF, path-tenant actor resolution and fresh tenant-wide
resolver authority for assessment, decision, history and receipt recovery. Source
access verifies original quotations outside short admission transactions. Read
authorization is a snapshot, as in ADR 0085; write/retry admission rechecks after
database lock waits. Requester sessions are denied. Strict JSON commands are bounded
to 16 KiB, responses use no-store, and missing/foreign/unauthorized identities are
unavailable. Direct database administration remains privileged; stored v1 codes
are validated against verified immutable claims on history reads.

## Validation

Cover interval edges, equal/unknown validity, differing scopes and text, strict
input, citation integrity, tenant isolation, exact retry, changed intent,
concurrent acceptance, authority expiry during locks, bounded sets, immutable
storage and upgrade/fresh migrations. Run focused tests/static checks, repository
policy and full verification; report Docker-unavailable coverage exactly.
