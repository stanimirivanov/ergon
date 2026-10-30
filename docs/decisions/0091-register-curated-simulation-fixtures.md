# ADR 0091: Register curated simulation fixtures

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

The v1 fixture manifest pins immutable artifacts but does not resolve them.
Registration needs durable complete replay inputs without importing production
cases, inventing a live model adapter or fetching arbitrary references.

## Decision

Store one bounded self-contained package per tenant/key/revision, including all
manifest artifact material as strict UTF-8 strings and explicit synthetic or
curated-historical provenance. Verify each content digest and the manifest digest
before registration. This certifies package integrity, not material semantics,
privacy, human authority, replay success or promotion eligibility.

Expose only an internal trusted-machine endpoint. A registered tenant actor is
claimed attribution; this endpoint does not establish production ingress or a
browser authorization boundary. Missing/foreign identities remain indistinguishable.

Use one additive immutable row with the original actor and database recording
instant. INSERT ON CONFLICT followed by a fresh READ COMMITTED read compares the
whole normalized package. Exact retry keeps original attribution, even when a
second registered actor submits it. Different provenance conflicts even if the
manifest semantic digest is unchanged.

## Alternatives considered

### Resolve references from production entities or remote URLs

- Benefits: Smaller uploads and shared existing storage.
- Costs and risks: Privacy, live-state coupling, SSRF and missing synthetic inputs.
- Reason not selected: Curated simulation identities must remain independent of
  production records. Remote resolution is unnecessary for this slice.

### Normalize the package with JSONB storage

- Benefits: Database JSON operators and structural indexing.
- Costs and risks: Representation expansion complicates exact byte limits and
  introduces a second normalization boundary without a query requirement.
- Reason not selected: Preserve bounded normalized JSON text; SQL casts validate
  the few mirrored invariants, while identity and actor remain relational.

## Consequences

### Positive

Atomic packages cannot leave partial material registrations. Existing runtime
storage and domain rules remain unchanged; no new dependency is introduced.

### Negative

Packages duplicate shared material and must fit the bounded ingress limit.
Historical privacy review remains the submitter's responsibility. There is no
record-retention/deletion workflow beyond the existing trusted database boundary.

### Neutral or follow-up

M07 replay consumers must validate supported material schemas. Later browser APIs
must establish fresh authenticated entitlements instead of forwarding actor headers.

## Compatibility and migration

The migration is additive and has no backfill or existing-table scan. Deploy it
before API code; rollback code while preserving rows and migration history.
Versioned normalized package meaning must not change in place. Existing contract,
case and source records are unaffected.

## Security and operations

Bound raw ingress reads and normalized bytes. Reject coercions, duplicate keys,
unknown fields and malformed UTF-8. Never fetch or execute content. Database
constraints include tenant actor foreign keys, canonical SHA-256 and tenant/schema
checks. Immutable triggers prevent UPDATE/DELETE; direct database privileges
remain a trusted operational boundary, not per-tenant authorization.

## Validation

Codec/service/HTTP tests cover normalization, completeness, integrity, malformed
input, bounds and tenant mismatch before storage. PostgreSQL tests cover empty
and upgrade migrations, original attribution, concurrent retry, conflict, foreign
actor and immutable storage. Docker-unavailable tests must be reported as unverified.
See the exact [registration contract](../simulation.md#internal-fixture-registration).
