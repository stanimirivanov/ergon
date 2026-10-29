# ADR 0087: Publish review-qualified evidence bundles

- Status: Accepted
- Date: 2026-10-29
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M06-PR10 needs an immutable documentary manifest before later contract consumption.
A selected accepted claim must not conceal accepted alternatives or reuse a stale
human preference. Publication must serialize with acceptance and preserve its
original cutoff rather than silently selecting current or latest evidence later.

## Decision

Publish schema ergon.dev/evidence-bundle/v1 with at most 20 explicitly selected
claims/procedures total. Claim selections include exact assessment anchor,
revision, canonical candidate IDs and optional adjudication identity from M06-PR8.
Use complete accepted sets under exact property/subject/scope keys, bounded to
20 candidates each; no ontology matching or real-world disjointness is inferred.

Require known validity on every assessed claim, including singleton sets. Preserve
half-open interval semantics and pinned documentary-contradictions-v1 findings.
Compatible/disjoint known sets need no adjudication. An undetermined comparison
cannot qualify, even with human preference. A known conflicting set qualifies
only when its exact current immutable adjudication prefers the sole selected
claim from that set. DEFER, missing/stale decisions and conflicting selections
fail closed. Procedures require exact accepted review and verified list/step/API
citations; their API links remain documentary, not executable capabilities.

Preserve all assessed alternatives as audit context, separately marking selected
entries. Pin proposal kind/UUID, extraction/compiler, exact primary source and
accepted review command, complete candidate/review arrays, pair codes and conflict
decision references. Include the exact source-revision union, including linked
procedure-operation sources. Referenced immutable proposals retain their complete
typed content and nested operation attribution; no quotations are copied or
interpreted under a later compiler.

Canonical v1 is compact UTF-8 JSON with explicitly ordered DTO fields, canonical
UUID/list ordering, literal booleans/nulls, and an ISO UTC microsecond cutoff.
An isolated serialization mapper prevents global HTTP settings from changing
these bytes. This is a versioned repository format, not a claim of JCS or PROV
conformance. Store the exact text and its SHA-256; reads verify hash and canonical
round trip. A protected canonical endpoint returns those bytes for independent
digest verification. The normal envelope provides provenance and parsed manifest.
The digest is of the canonical manifest, not the enclosing HTTP message.

Preparation reauthorizes source/citation reads outside the final short transaction.
Admission first locks the bundle publication identity (including procedure-only
first publication), then every claim key in a shared C-collation order. Acceptance
and adjudication already advance/lock those keys. Recheck selected resolver proof
using database time after waits; SQL admission repeats exact membership, review,
source, qualification, decision and cutoff checks. Recompute the bounded findings
in SQL so forged codes cannot hide unknown or contradictory inputs.

Tenant-safe proposal/review, source and adjudication ledgers commit with the
manifest. Deferred checks require exact completeness and forbid later additions;
all durable rows are immutable. An acceptance either precedes publication and
invalidates stale inputs, or follows publication and leaves a historical snapshot.
Assessments do not claim global consistency outside their explicit keys/cutoff.

Same-actor exact publication retries recover original publisher, proof, cutoff,
recording time and bytes after fresh authorization, without current qualification
or candidate discovery. Changed intent conflicts. History reads reauthorize access
but never reinterpret later acceptance/adjudication as a modification of the bundle.

## Alternatives considered

### Publish selected claims without their complete candidate context

- Benefits: smaller manifest and fewer checks.
- Costs and risks: omitted alternatives could masquerade as resolved consistency.
- Reason not selected: preserve complete bounded assessments and require current
  explicit preference where known conflicts exist.

### Reevaluate published bundles against latest evidence

- Benefits: apparent freshness without explicit new publication.
- Costs and risks: changes what a pinned contract or historical run consumed.
- Reason not selected: new evidence requires a separately identified publication.

### Add an external provenance or workflow engine

- Benefits: broader graph/workflow capabilities.
- Costs and risks: licensing/dependency/operations weight without required behavior.
- Reason not selected: adapt existing immutable revision/receipt and derivation
  patterns from ADR 0082, using existing PostgreSQL/Jackson; no dependency or wrapper.

## Consequences

### Positive

A review-qualified snapshot is verifiable, attributable and independently
retrievable. Later consumers can pin a bundle without implicit latest selection.

### Negative

Unknown validity on any accepted alternative blocks new publications for that key;
preference cannot erase that uncertainty. This includes accepted legacy claims
with absent bounds. In-place corrections and supersession remain unavailable.
Publication can conflict under concurrent acceptance; clients must reassess
explicitly and use a new bundle identity rather than silently altering intent.
Bounded synchronous preparation may perform multiple private source reads.

## Compatibility and migration

Apply additive V20261029220000 before enabling the API. Existing reviews and
adjudications keep their meaning; no backfill, contract mutation, promotion,
worker or execution permission is introduced. The migration adds new immutable
aggregates and relational reference ledgers, with no rewrite of existing evidence.
Reverting API exposure retains publication history; never reverse a shared migration.

## Security and operations

Use existing workbench OIDC/CSRF and current tenant-wide resolver authority.
Self-publication is permitted under the existing resolver policy. Requests are
strict JSON bounded to 32 KiB; canonical manifests are bounded to one MiB and
420 proposal/source references. Responses use no-store. Missing/foreign/unaccepted
private selections are unavailable; stale or changed intent conflicts. Unsupported
qualification is explicit, while database infrastructure failures propagate.
Only explicit authority/stale/qualification SQLSTATEs receive domain mappings.
Bundle IDs, hashes and documentary acceptance never grant execution authority.

## Validation

Test deterministic manifest/digest round trips, known versus unknown validity,
typed conflicts, explicit preferred selection, linked sources, rejected procedures,
strict input, authorization ordering, exact historical retry, partial/late ledgers,
concurrent acceptance/publication, procedure-only first publication and expiry
during identity-lock waits. Verify upgrade and fresh migration chains. Run focused
tests/static gates, repository policy and full verification; report Docker skips
as unverified PostgreSQL behavior, never as passing database coverage.
