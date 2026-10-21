# Supervisor-scoped pinned contract

## TL;DR

Current run supervisors with current tenant-wide RESOLVER evidence can inspect
the exact immutable contract revision behind their Console. This is normalized
declaration, not original YAML, assessed readiness, effective policy or execution.

## Browser contract

`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/pinned-contract`
uses the confidential session. It returns `runId`, `supervisionRevision`,
`observedAt`, `pinnedStepId`, `contractRecordedAt` and `contract`.
The epoch and time describe an authorization observation, not continuing access
or control. The contract recording instant is database metadata, not execution
or a duration input; wall-clock correction can reverse apparent time ordering.

The normalized contract has `schema=ergon.dev/resolution-contract/v1alpha1`,
`key`, `revision`, `applicability`, `requiredEvidence`, ordered `steps` and
`outcomeProof`. Conditions contain `fact` and exact case-sensitive `equals`.
Each step contains `id`, `capability`, declared `risk` and declared `approval`.
Existing domain invariants bound required evidence and steps to 1–50, preserve
unique declaration order and bound identifiers/equality values.

## Meaning and limits

Only the run's exact tenant/key/revision is read, never latest or an arbitrary
browser-selected definition. Its pinned step must match a declared capability.
Runtime policy may strengthen risk/approval; the declarations are not its
effective safeguards, a current approval decision or a grant.

Applicability and required evidence are instructions, not a fresh readiness
assessment or historical selection explanation. Ordered steps are not a DAG,
causal trace, scheduled work or performed actions: the current runtime pins one
step. The outcome condition is not an assessment or accepted proof. Original
authoring YAML/comments are not retained and cannot be reconstructed faithfully.
Definition strings remain untrusted inert display text.
Inspection does not revalidate reference registration or tenant capability
availability; it never certifies that a declared action can currently run.

## Access and failures

Current exact-run supervision and tenant-wide resolver evidence are checked before
private run/contract lookup, including for terminal runs. No browser control,
originating actor, CSRF setup or enabled execution command is required.
Missing/foreign/unassigned/former-supervisor/expired access shares neutral
no-store `404 assigned-resolution-run-not-found`. Session actor mapping retains
safe 403 identity failures; it does not reveal cross-tenant registration.

Malformed UUIDs return no-store `400 invalid-run-pinned-contract`. Storage or
durable attribution failure returns cause-free no-store
`503 run-pinned-contract-unavailable`, never successful absence or a newer
revision fallback. Disabled browser sessions return no-store
`503 browser-authentication-unavailable`. Success is also no-store.

## Deployment and follow-up

No migration, dependency, new flag or persisted meaning changes. Existing
immutable repositories/codecs supply the bounded definition within a short
local transaction; no remote call occurs. Deploy backend before the dependent
UI and guide, then preserve fresh-access/retirement rules in that implementation.
Old missing routes are unsupported, not empty or an internal API fallback.
Rollback removes presentation/read access without deleting run or contract data.

See [ADR 0061](decisions/0061-disclose-supervisor-scoped-pinned-contract.md).
Application and actual PostgreSQL/HTTP tests cover scope, consistency, lifecycle,
privacy and safe failures. Synthetic sessions do not certify production identity,
provider safety, contract promotion or multi-step execution.
