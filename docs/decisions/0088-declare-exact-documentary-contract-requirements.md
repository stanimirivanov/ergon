# ADR 0088: Declare exact documentary contract requirements

- Status: Accepted
- Date: 2026-10-30
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M06-PR12 needs an immutable contract declaration of the documentary bundles
published under ADR 0087. Existing requiredEvidence names operational facts;
reusing it would misrepresent documentary acceptance as live account state or
outcome proof. Existing v1alpha1 documents and stored revisions must retain their
meaning. Case-bound documentary consumption belongs to M06-PR13.

## Decision

Add schema ergon.dev/resolution-contract/v1alpha2. Preserve all operational fields
and require documentaryEvidence containing 1–20 exact entries with bundleId and
sha256. The UUID is canonical lowercase; the digest is the lowercase SHA-256 of
the canonical historical manifest, not its response envelope. Bundle identities
are unique within a contract, declaration order is preserved, and there is no
latest alias, implicit selection, cross-tenant reference or optional requirement.

An entry requires that exact published bundle as documentary input. Publication
checks existence and digest in the contract tenant; it neither evaluates case
applicability nor requalifies the historical manifest against later accepted
claims/adjudications. Bounds and applicability at consumption time remain the
future consumer's responsibility. Acceptance and explicit preference remain human
documentary judgments, never operational truth, authority or outcome proof.

Keep the original v1alpha1 grammar and normalized response/definition shape.
It rejects documentaryEvidence, even empty or null. v1alpha2 rejects missing or
empty documentary requirements instead of silently serializing as v1alpha1.
Unsupported schemas fail closed. Strict schema-selected JSON readers reject
unknown/missing fields, null primitives and trailing/duplicate input. Native
operational-only definitions retain v1alpha1 serialization; adding documentary
requirements returns a new immutable native definition. Publication cannot
replace an existing tenant/key/revision.

Use the existing safe bounded YAML loader, domain constructors, reference
registry and Jackson/PostgreSQL adapters. No dependency or protocol framework
is added. Contracts consume an application-owned exact bundle lookup port; its
adapter uses the evidence repository's integrity-checked historical read. It
does not read another module's tables as an informal application API. Existing
internal machine publication remains its security boundary; no browser authoring
endpoint, curator role or new human-authority policy is introduced.

Store normalized documentary references in the versioned definition. An AFTER
INSERT trigger projects the complete reference ledger in the same statement.
Composite foreign keys enforce both the parent contract and the exact tenant,
bundle UUID and manifest digest. A child admission guard plus its primary key
forbid later extra links; UPDATE/DELETE remain forbidden. Failed references
roll back parent and projection together. Immutable bundle rows make the
application lookup safe from deletion or mutation races before admission.

Until M06-PR13 implements case-bound consumption, documentary contracts return
DOCUMENTARY_EVIDENCE_UNSUPPORTED before operational fact evaluation. They expose
no applicability conclusion or next step and cannot start a run. The run-plan
boundary independently rejects a documentary contract even if injected planning
claims operational readiness. A database BEFORE INSERT guard also rejects new
runs referencing v1alpha2. This is an explicit compatibility gate, not a pretend
readiness implementation. PR13 must replace the evaluator and database guard
with verified case-pinned evaluation before enabling these runs.

Normalized internal validation/publication/get responses identify their actual
schema and include exact documentary references only for v1alpha2. The protected
pinned-contract projection also preserves schema and declarations rather than
labeling a documentary definition v1alpha1. Existing reader schemas may reject
new documentary shapes; rejection must never become partial interpretation.

## Alternatives considered

### Extend v1alpha1 or map documents to operational requiredEvidence

- Benefits: fewer schema branches.
- Costs and risks: older consumers could silently ignore material requirements
  or mistake documentary assertions for connector observations.
- Reason not selected: preserve published operational meaning and explicit denial.

### Require only a bundle UUID or resolve the latest qualified bundle

- Benefits: less input and easier authoring.
- Costs and risks: hides the exact manifest or changes historical interpretation.
- Reason not selected: pin both immutable identity and canonical digest explicitly.

### Add another evidence engine or provenance dependency

- Benefits: broader schema/provenance tooling.
- Costs and risks: weight without current behavior requiring it.
- Reason not selected: reuse existing strict decoding and immutable publication
  patterns; no new dependency, interface wrapper around a vendor or copied code.

## Consequences

### Positive

Contracts can publish attributable documentary requirements without changing live
fact, proof or execution semantics. Storage enforces complete tenant-safe exact
references, while standalone validation remains context-free.

### Negative

Authors must already know exact published bundle IDs/digests. The initial grammar
requires a new revision to change a requirement. Documentary contracts can be
published and pinned to cases but remain deliberately non-runnable until PR13;
this slice supplies no case bundle event or readiness satisfaction.

## Compatibility and migration

Apply V20261030100000 before deploying these writers. It expands the contract
schema constraint, adds a bundle identity/digest unique constraint and creates the
immutable ledger/triggers. Existing definitions, bundles, case pins and runs are
not rewritten or backfilled. Constraint validation and unique-index creation scan
existing rows and require DDL locks; schedule before enabling new publication.
No new long transaction or remote call is introduced.

Deploy readers and the fail-closed runtime before clients publish v1alpha2 in a
mixed-version deployment. Older readers reject that schema. Rollback may retain
the expanded database, but older applications cannot serve new-schema revisions;
stop new publication and use compatible readers or roll forward. Never down-migrate
or delete immutable revisions to obtain rollback. PR13 owns removal/replacement
of the documentary run gate with a forward migration.

## Security and operations

Internal endpoints retain their existing trusted-machine/development exposure;
this change does not make them public or production-safe. Missing, foreign and
digest-mismatched bundles share a non-disclosing HTTP 422 problem and field path.
Infrastructure/integrity failures remain operator errors. Registry validation for
operational facts/capabilities still runs independently. A manifest reference,
contract pin or unsupported-readiness response grants no execution authority.

## Validation

Exercise both examples, strict mixed-schema/unknown/malformed input, preserved
legacy normalized responses, codec round trips, tenant/digest publication denial,
immutable complete links and atomic rollback. Cover independent empty migration
and an upgrade containing a published v1alpha1 revision and PR10 bundle. Verify
unsupported readiness, absence of planning/run side effects, injected-ready denial
and the database run guard. Run focused tests/static checks, repository policy
and full verification; Docker-unavailable PostgreSQL coverage is unverified.
