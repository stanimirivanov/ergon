# Resolution contract documents

## TL;DR

A resolution contract is strict YAML declaring when a resolution applies, the
evidence it needs, its ordered capability requests, and the fact that proves
success. `v1alpha2` additionally requires exact case-attached documentary bundles.
Attachment-time suitability and operational evidence remain independent. Validation
grants no authority and performs no action.

The executable example is
[`examples/contracts/restore-workspace-access.yaml`](../examples/contracts/restore-workspace-access.yaml).
Validate it with `POST /internal/v1/resolution-contracts/validate` using
`Content-Type: application/yaml`.

## `v1alpha1` shape

| Field | Meaning |
|---|---|
| `schema` | Must be `ergon.dev/resolution-contract/v1alpha1`. |
| `key` | Stable lowercase contract identity; revisions share it. |
| `revision` | Positive integer identifying this definition. |
| `applicability` | Fact equality required before this contract can apply. |
| `requiredEvidence` | Unique fact names that must be available for selection. |
| `steps` | One to 50 ordered capability requests. Step IDs are unique. |
| `outcomeProof` | Fact equality a later runtime must observe before resolution. |

A condition has `fact` and `equals`. Fact and capability names are qualified,
lowercase identifiers such as `account.access.state` and
`identity.account.unlock`.

Each step declares:

- `id`: stable within the revision;
- `capability`: requested operation, not an authorization grant;
- `risk`: `LOW`, `MEDIUM`, or `HIGH`;
- `approval`: `NONE`, `REQUESTER`, or `RESOLVER`.

`HIGH` risk cannot use `NONE`. Runtime policy may increase risk or require more
authority; a contract can never weaken policy.

## `v1alpha2` documentary requirements

Use `schema: ergon.dev/resolution-contract/v1alpha2` with the same operational
fields and a required `documentaryEvidence` array of 1–20 entries:

```yaml
documentaryEvidence:
  - bundleId: "11111111-1111-4111-8111-111111111111"
    sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
```

These are synthetic values. Replace them with an exact bundle already published
in the contract's tenant and its canonical manifest SHA-256. The
[structural example](../examples/contracts/restore-workspace-access-with-documentary-evidence.yaml)
is validated by tests; it cannot publish until its references exist. UUIDs and
digests are canonical lowercase, bundle IDs cannot repeat, and order is retained.
Missing, empty, unknown, duplicate and malformed declarations are rejected.
v1alpha1 rejects this field entirely; it is never silently ignored or upgraded.

A reference requires that exact historical documentary manifest. Publication
checks its tenant/identity/digest but does not reassess later accepted evidence or
evaluate applicability at consumption time. Source/review attribution remains in
the immutable bundle. This is separate from live operational `requiredEvidence`
and `outcomeProof`, and cannot supply an account-state fact or action authority.

New normalized responses use the actual schema and include the requirements;
v1alpha1 responses retain their original field set. Stored definitions use strict
schema-selected decoders; unsupported schemas fail closed. Publish a new contract
revision to change a documentary requirement, never edit a published revision.

Attach an exact published bundle to an open case with
`POST /internal/v1/tenants/{tenantId}/cases/{caseId}/documentary-bundles`, a quoted
current stream version in `If-Match`, and JSON `bundleId` plus `sha256`. The response
returns the new case version/ETag. Missing/foreign/digest-mismatched bundles share
404; stale versions return 412. Each case supports 20 distinct immutable bundle
attachments, once per identity. This is an internal case command, not a browser API.

Readiness returns each requirement as `MISSING`, `UNSUITABLE` or `SATISFIED` with
its attachment stream version and authoritative database recording time. Claims
must be valid at that attachment instant: unknown bounds, future validity or
expired half-open intervals are unsuitable. Procedure-only bundles require no
claim interval. Unsuitable material may be attached, but cannot qualify a run.

Suitability is historical at attachment. Later time, accepted alternatives and
adjudications do not reinterpret the fixed manifest or pin; post-prefix attachments
do not affect earlier runs. All selected claims must apply at the same instant,
so disjoint temporal selections may make a published bundle unsuitable as a whole.
Documentary satisfaction does not supply operational facts, applicability, human
approval, capability authority or post-action outcome proof.

## Validation boundary

Documents are limited to 50,000 code points and 20 nesting levels. YAML is
loaded with safe types, collection aliases disabled, duplicate keys rejected,
and unknown or missing fields rejected. Failure returns HTTP 422 with problem
type `urn:ergon:problem:invalid-resolution-contract` and a `violations` array.

Success returns a normalized JSON representation. This validation operation
does not store a revision, check that named facts or capabilities are
registered or documentary bundles exist, select the contract for a case, authorize a step, or execute
anything.

## Publishing revisions

Publish a validated revision with
`POST /internal/v1/tenants/{tenantId}/resolution-contracts`; the body uses the
same YAML content types. The response is HTTP 201 with a `Location` for the
exact revision and its database recording time. Read it through
`GET /internal/v1/tenants/{tenantId}/resolution-contracts/{key}/revisions/{revision}`.

The `(tenant, key, revision)` identity is immutable. Reusing it returns HTTP
409 even when the new document differs, and another tenant cannot discover the
revision through its own path. PostgreSQL stores normalized, schema-versioned
JSON meaning rather than authoring YAML and rejects UPDATE or DELETE.

For v1alpha2, missing, foreign-tenant and digest-mismatched documentary references
all return HTTP 422 with problem type
`urn:ergon:problem:unavailable-contract-documentary-evidence` and a generic field
violation. Normalized definitions and complete relational links commit atomically;
tenant/identity/digest foreign keys and immutable triggers protect direct writes.

Publication also requires every fact and capability name to be present in the
system reference registry. The first registry recognizes
`account.access.state` and `identity.account.unlock`. Unknown occurrences are
returned together as HTTP 422 with problem type
`urn:ergon:problem:unregistered-contract-references` and their document paths.
Registration establishes stable system-wide meaning only; it does not prove
that a tenant has an evidence source, installed connector, or authority.

Pin a published revision with
`POST /internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-contract`, a
JSON body containing `key` and `revision`, and the quoted case stream version
in `If-Match`. The pin is an immutable case event and appears as
`resolutionContract` in the case timeline response; it is not source evidence
and therefore does not add a timeline entry. A case can be pinned once, and a
revision from another tenant is indistinguishable from an absent revision.

Publishing or pinning still does not establish tenant availability, promote or
automatically select a revision, authorize a step, or execute it. Revisions
published before the registry gate were introduced are not retroactively
certified; a later runtime must resolve every reference and fail closed before
execution.

## Inspect a pinned contract

The separate supervisor-scoped
[`GET .../{runId}/pinned-contract`](run-pinned-contract.md) inspects only the
exact immutable revision pinned by a currently authorized run. It preserves
normalized declarations, not original YAML or assessed readiness. Declared step
risk/approval can differ from strengthened runtime policy, and ordered requests
are not an execution trace. Its dependent Console UI and guide are merged.

## Readiness

The separate [pinned-run readiness inspection](run-pinned-readiness.md) evaluates
only the exact immutable contract and case prefix of a currently supervised run,
with an explicitly versioned evaluator and narrowly selected citation. It does
not claim to recover stored historical selection reasoning or current-case readiness.
The internal query below deliberately has a different, latest-case boundary.

Query
`GET /internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-readiness` to
evaluate the pinned revision against one authoritative case-event snapshot.
The response identifies the exact case stream version and one of:

- `WAITING_FOR_DOCUMENTARY_EVIDENCE`: exact required case-prefix attachments are missing;
- `UNSUITABLE_DOCUMENTARY_EVIDENCE`: attachments exist but fail exact identity or attachment-time suitability;
- `WAITING_FOR_CONTRACT`: no revision is pinned;
- `WAITING_FOR_EVIDENCE`: one or more required fact types have no value;
- `NOT_APPLICABLE`: all evidence exists but applicability equality is false;
- `READY`: all evidence exists and applicability equality is true.

Missing documentary input precedes unsuitable documentary input; both precede
operational missing evidence and applicability. For operational evaluation,
the latest bound `account.access.state` in stream order supplies that registered
fact; equality is exact and case-sensitive. The response includes the expected
and actual applicability values only after all required evidence exists.

Readiness is a deterministic query, not durable execution state. `READY` does
not check tenant connector availability, authorize the capability, create a
run, or prove the outcome. A caller acting on it must use the returned case
stream version as the snapshot boundary and revalidate all runtime gates.

The schema identifier is a compatibility boundary. Published field meanings do
not change in place; incompatible syntax or semantics require a new identifier
and an ADR with coexistence and migration rules.


## Documentary deployment and rollback

Apply V20261030100000 for declaration/publication and V20261030120000 for case
attachments and consumption. Deploy compatible readers before enabling writers;
older readers reject new case events. Existing definitions, bundles and run
prefixes are not backfilled or rewritten. PR13 replaces all PR12 interim
unsupported-readiness/application/database guards with exact case-bound evaluation.

The database enforces complete immutable event projections and tenant/bundle/digest
references, then independently checks case version, contract pin, prefix membership
and attachment-time suitability on documentary run admission. App rollback must
retain compatible readers or roll forward; do not delete history or reverse shared
migrations. See [ADR 0089](decisions/0089-consume-case-pinned-documentary-evidence.md).

## Explicit contract promotion

M07-PR10 adds a separate promotion ledger; publication, case pinning and run
admission keep their existing behavior. See [ADR 0099](decisions/0099-record-authorized-contract-promotions.md).
A trusted machine first attests a registered actor's dedicated tenant-wide
CONTRACT_PROMOTER entitlement. RESOLVER does not inherit it. Attestations record
source provenance, server time and an explicit expiry up to 24 hours later, at
microsecond precision. PR12 adds [append-only revocation](simulation.md#authenticated-simulation-lab-browser-contracts);
renewal records new evidence and cannot resurrect a revoked evidence ID.

All routes below are under
`/internal/v1/tenants/{tenant}/contract-promotions` and remain trusted internal
machine APIs, not browser authentication boundaries:

| Method/path | Request / result |
|:--|:--|
| POST `/authorities/{id}` | `ergon.contract-promotion/authority/v1`: actorId, sourceProvider, sourceReference, expiresAt (ISO instant string) |
| GET `/authorities/{id}` | Recorded immutable authority evidence |
| POST `/requirements/{key}/commands/{id}` | `ergon.contract-promotion/required-suite/v1`: expectedVersion, suite, rationale |
| GET `/requirements/{key}` | Highest binding version and its original attribution |
| POST `/commands/{id}` | `ergon.contract-promotion/command/v1`: expectedVersion, suite, contract, policy, reports, rationale |
| GET `/commands/{id}` | Original immutable promotion receipt |

Every POST body includes `schemaVersion`. Binding and promotion commands require
`X-Ergon-Actor-Id`, whose current dedicated authority is checked after acquiring
transaction locks. Attestation routes must be restricted to the trusted authority
issuer; source strings themselves are not verified credentials.

Artifact references contain `key`, `revision` and `sha256`. Each selected report
contains `fixture` (artifact reference), `reportId` and `reportDigest`, matching
[PR9 eligibility](simulation.md#required-suites-and-promotion-eligibility).
Rationale is explicit, trimmed, nonblank and at most 2,000 characters. Bodies are
strict UTF-8 JSON up to 128 KiB. Unknown fields and caller-supplied pass claims fail.

Bind a suite separately before promoting. First binding uses expectedVersion 0;
subsequent commands require the current version and increment it, including an
explicit reconfirmation of the same suite. The suite must belong to that published
contract key. Promotion requires the binding's current version and exact suite,
then independently checks all selected reports against its immutable policy.
The database serializes binding and promotion, rechecks authority and qualification,
and commits the receipt and complete report links atomically.

New commands return 201; exact same-actor retries return 200 with original time and
authority proof. A retry requires current entitlement but can recover historical
receipts after a binding change. It is not fresh admission. Changed intent or actor
and stale versions return 409; unavailable authority/references return 404;
ineligible or malformed commands return 422; oversized bodies return 413. Responses
use `Cache-Control: no-store`. GETs retrieve recorded facts, not current authority.

Apply V20261030180229 before these writers. Roll back application code with command
writers disabled and preserve immutable history. [PR11 enforcement](operations/contract-promotion.md) requires current-binding promotion
for fresh starts and successors in the configured tenant allowlist.
