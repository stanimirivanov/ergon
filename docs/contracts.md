# Resolution contract documents

> **TL;DR:** A `v1alpha1` contract is strict YAML declaring when a resolution
> applies, the evidence it needs, its ordered capability requests, and the fact
> that proves success. Validation grants no authority and performs no action.

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

## Validation boundary

Documents are limited to 50,000 code points and 20 nesting levels. YAML is
loaded with safe types, collection aliases disabled, duplicate keys rejected,
and unknown or missing fields rejected. Failure returns HTTP 422 with problem
type `urn:ergon:problem:invalid-resolution-contract` and a `violations` array.

Success returns a normalized JSON representation. This validation operation
does not store a revision, check that named facts or capabilities are
registered, select the contract for a case, authorize a step, or execute
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

## Readiness

Query
`GET /internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-readiness` to
evaluate the pinned revision against one authoritative case-event snapshot.
The response identifies the exact case stream version and one of:

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
