# ADR 0082: Define documentary evidence boundaries

- Status: Accepted
- Date: 2026-10-29
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M06 needs attributable document evidence without changing the meaning of existing
connector observations, account-access facts, approval evidence or outcome proof.
The approved M06-PR1 foundation defines citation invariants before persistence
and parsing. A source quotation establishes where an assertion came from; it
cannot establish that the assertion is true or that an action is authorized.

## Decision

Use framework-free evidence values in domain-kernel. A source revision includes
tenant, logical source UUID and lowercase SHA-256 of original bytes. Empty sources
are valid; no citation into an empty source is valid. UUIDs are opaque identities,
not authority. An extraction records its own UUID, exact source revision and a
bounded explicit extractor revision; it must never use an implicit latest version.

Citation schema v1 uses non-empty half-open Unicode code-point offsets into strict
UTF-8 decoded original bytes plus an exact quotation. Preserve CRLF, BOM and Unicode
normalization forms. Malformed UTF-8 fails closed. Citation verification requires
an exact tenant/source/digest match, valid bounds and exact quoted text. Adapters
must use this verification before accepting parser or model-proposed citations.
Wire serialization and persisted schemas will be separate adapter concerns.

Adapt W3C PROV derivation and Web Annotation position/quotation selector patterns
without copying implementations or adding RDF dependencies. This is not a claim
of standards conformance. See [PROV-O](https://www.w3.org/TR/prov-o/) and
[Web Annotation](https://www.w3.org/TR/annotation-model/).

Initial formats are UTF-8 CommonMark and OpenAPI 3.0 JSON. Later adapters must bound
input size, nesting, reference depth, compilation output and execution time;
external references must never cause implicit network or filesystem access.
Parsing returns source observations. Separate semantic binding proposes claims
and ordered procedures; it must not depend on parser infrastructure types.

Proposal labels are PROPOSED, ACCEPTED and REJECTED. They describe documentary
review, not operational truth. This PR does not implement review transitions.
The proposed later reviewer policy is fresh tenant-wide resolver authority plus
explicit action on an exact proposal revision. Confirm that policy in M06-PR7;
these value types alone confer no read, review, publication or execution rights.

For the bounded first release, compile synchronously within control-plane, with
bounded source storage in PostgreSQL. This stages the compiler-worker/object-store
target in [architecture](../architecture.md) rather than introducing an unused
process or infrastructure now. Separate deployment and object storage require a
follow-up decision when supported formats, isolation or measured load require them.
No infrastructure or storage is added by this foundation.

## Alternatives considered

### Reuse connector facts and authority evidence

- Benefits: fewer new types.
- Costs and risks: documents could masquerade as current account state or grants.
- Reason not selected: attribution and authorization have different invariants.

### Byte or JVM character offsets without exact quotations

- Benefits: simpler indexing in one implementation.
- Costs and risks: ambiguous Unicode boundaries and undetected citation drift.
- Reason not selected: code-point positions and exact quotes provide verifiable attribution.

### Add a general ingestion framework and separate worker immediately

- Benefits: broad format support and process isolation.
- Costs and risks: dependencies and operations before a usable compiler slice.
- Reason not selected: start with bounded formats and revisit deployment explicitly.

## Consequences

### Positive

Deterministic citation checks reject drift across tenants, revisions and Unicode
representations. Domain types remain independent of transport and provider choices.

### Negative

Adapters must translate parser coordinates explicitly. Digests do not authenticate
sources. Synchronous compilation will need strict limits and may later need isolation.

### Neutral or follow-up

M06-PR2 adds immutable storage and authenticated source APIs. M06-PR3/PR4 add parsers;
PR5–PR8 add semantics and human decisions. No model provider or confidence score is
introduced. A later documentary contract requirement must remain distinct from
operational required evidence and cannot bypass policy or independent outcome proof.

## Compatibility and migration

No current event, contract schema, API, table or runtime behavior changes. The new
values are not serialized by existing adapters. Later persistence and HTTP PRs must
version their shapes explicitly and preserve these coordinate semantics. Reverting
this foundation before those consumers arrive requires no migration.

## Security and operations

Sources, quotations and model output are untrusted and potentially confidential.
Tenant scope is part of every source identity, not an authorization check. Later
adapters enforce access and size limits; no source fetch, logging or rendering is
introduced. Acceptance must never create a capability grant or post-action proof.

## Validation

Domain tests cover known SHA-256 bytes, defensive input copying, strict UTF-8,
Unicode supplementary and combining characters, preserved CRLF, exact source
identity, invalid spans, altered quotations and malformed extractor identifiers.
Run repository policy and full Maven verification. Parser network isolation,
storage immutability and reviewer concurrency tests ship with their later slices.
