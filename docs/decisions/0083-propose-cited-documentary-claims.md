# ADR 0083: Propose cited documentary claims

- Status: Accepted
- Date: 2026-10-29
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

ADR 0082 separates document attribution from operational truth and authority.
M06-PR5 needs a small typed vocabulary and durable proposal identity before review
and contradiction assessment. Arbitrary caller quotations or untyped facts could
otherwise masquerade as compiler output or live account state.

## Decision

Bind a selected Markdown extraction observation explicitly. Recompute the exact
source projection under current source authorization and verify its citation.
Require the extraction UUID and zero-based observation index; never trust a caller
quotation. Persist source/extraction/compiler identity, selection, quotation,
proposer, database time and binding rule `manual-claim-v1` as immutable evidence.
The tenant-wide proposal UUID is an idempotency identity: exact same-actor retries
recover original provenance, while changed commands or actors conflict.

Register `documentation.access.restoration.supported` as a boolean assertion and
`documentation.access.restoration.requirement` as bounded, nonblank single-line
text. These describe what documentation asserts, not account state or accepted
policy. Subject and scope are exact bounded labels without control characters;
no normalization, ontology matching or inferred applicability occurs.

Both validity bounds absent means unknown validity. Otherwise use an increasing
half-open interval within years 0001–9999 with microsecond-aligned instants so PostgreSQL round trips do
not change meaning. Compilation, occurrence, validity and recording times remain
distinct. Every result is PROPOSED; review transitions belong to M06-PR7, and these
rows cannot supply execution authority or independent outcome proof.

## Alternatives considered

### Caller-supplied free text facts

- Benefits: smaller input contract.
- Costs and risks: fabricated citations and ambiguous operational meaning.
- Reason not selected: require verified compiler selection and typed values.

### Reuse operational account access bindings

- Benefits: existing storage and readiness queries.
- Costs and risks: documentary assertions could replace live connector evidence.
- Reason not selected: keep distinct ownership and immutable proposal storage.

## Consequences

### Positive

Later review can inspect exact source provenance and typed, stable meaning.
Tenant-safe foreign keys and immutable rows preserve historical attribution.

### Negative

This initial binding accepts Markdown only; OpenAPI remains separately inspectable.
New vocabulary, ontology relationships and review semantics require explicit work.

### Neutral or follow-up

M06-PR7 adds review decisions, PR8 assesses overlapping scoped claims, and PR10
publishes qualified bundles. Unknown validity is not proof of consistency.

## Compatibility and migration

Add a new table and browser endpoints without changing existing source, case or
contract meaning. Apply the migration before application rollout; rollback retains
proposal rows. Never reverse a shared migration or mutate published provenance.

## Security and operations

Reuse current tenant-wide resolver checks and workbench session/CSRF boundaries.
Private reads reauthorize and verify citations; missing and foreign proposals are
indistinguishable. Parser and persistence adapters do not own semantic policy.
No model, network call, source rendering or acceptance authority is introduced.

## Validation

Tests cover typed vocabulary, invalid intervals, stale selection, authorization
ordering, immutable retry recovery, tenant isolation and migration upgrades. Run
repository policy, affected tests/static checks and full Maven verification; report
Docker-unavailable database checks as unverified rather than passed.
