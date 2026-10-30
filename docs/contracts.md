# Resolution contract documents

## TL;DR

A resolution contract is strict YAML declaring when a resolution applies, the
evidence it needs, its ordered capability requests, and the fact that proves
success. `v1alpha2` additionally pins exact documentary bundles and remains
non-runnable until case-bound documentary consumption is implemented. Validation
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

Until M06-PR13 delivers case-bound consumption, readiness returns
`DOCUMENTARY_EVIDENCE_UNSUPPORTED` with no applicability conclusion or next step.
This takes precedence over operational evidence availability. New runs are denied
by both application and database guards. Publishing or pinning such a contract
is therefore inspectable configuration history, not execution enablement.

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

- `DOCUMENTARY_EVIDENCE_UNSUPPORTED`: the pinned contract requires documentary
  consumption not yet implemented; no operational readiness conclusion is made;
- `WAITING_FOR_CONTRACT`: no revision is pinned;
- `WAITING_FOR_EVIDENCE`: one or more required fact types have no value;
- `NOT_APPLICABLE`: all evidence exists but applicability equality is false;
- `READY`: all evidence exists and applicability equality is true.

Missing evidence takes precedence over applicability. For the current slice,
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

Apply additive V20261030100000, then deploy schema-aware readers and fail-closed
runtime guards before clients publish v1alpha2. The migration validates/scans the
existing contract schema constraint and builds the exact bundle identity/digest
unique constraint; schedule its DDL locks before enabling writers. Existing
v1alpha1 definitions, bundles and runs are unchanged. No backfill is required.

Older applications reject v1alpha2 rather than partially interpreting it. An app
rollback retaining new records needs compatible readers or a forward recovery;
do not delete history or reverse a shared migration. PR13 must replace the
unsupported-readiness and database run-start guards when it adds case-bound
consumption. See [ADR 0088](decisions/0088-declare-exact-documentary-contract-requirements.md).
