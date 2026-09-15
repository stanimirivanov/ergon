# Resolution runs

> **TL;DR:** Starting a run freezes the ready case version, contract revision,
> policy revision, first step, and effective safeguards in one immutable row.
> Capability receipts append immutable run events and advance a transactional
> state projection. Connector success means `VERIFYING`, never resolved.

## Start and retrieve

Start a run with
`POST /internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-runs` and the
quoted ready case version in `If-Match`. Retrieve the returned location with
`GET /internal/v1/tenants/{tenantId}/resolution-runs/{runId}`.

The command recalculates readiness and policy inside its transaction. It locks
the case projection before inserting, so evidence appended after the caller's
snapshot produces `412 Precondition Failed` instead of a run based on mixed
versions. Incomplete or inapplicable evidence and policy denial prevent the
write. One case can currently start only one run.

## Pinned meaning

The immutable start records:

- the final case event included as evidence;
- exact contract and policy revisions;
- first step identity and capability;
- effective risk and required approval;
- the resulting initial requirement state.

Human approval produces `WAITING_FOR_APPROVAL`. No human approval would produce
`READY_FOR_AUTHORIZATION`, which still requires later actor-scope and tenant-
capability checks. Neither initial state alone permits tool invocation;
execution requires the separate approval, grant, and consumption chain.

The start row is not current execution state. Its initial value seeds a separate
version-zero projection while the row itself remains immutable.

## Record a capability result

After invocation has made a terminal receipt durable, call
`POST /internal/v1/tenants/{tenantId}/resolution-runs/{runId}/capability-results`.
The first append returns `201`; replay returns `200` with the same event. Calling
before a receipt exists returns `409`, and tenant scope is enforced as `404`.

The command locks the current-state projection and atomically appends sequence
one plus projection version one. Receipt and run must agree on tenant, run,
case, policy revision, step, and capability. Database constraints additionally
bind event outcome and occurrence time to the exact receipt.

`SUCCEEDED` appends `CAPABILITY_SUCCEEDED` and moves to `VERIFYING`.
`FAILED` appends `CAPABILITY_FAILED` and moves to `ACTION_FAILED`. Neither path
changes case status. Verification observations, accepted outcome proof, retry,
compensation, and later run transitions remain separate capabilities.

## Assess outcome proof

For a run in `VERIFYING`, query
`GET /internal/v1/tenants/{tenantId}/resolution-runs/{runId}/outcome-proof`.
The response evaluates the pinned contract condition at an exact current case
stream version and returns `ACCEPTED` or `PENDING`.

Proof cannot reuse the evidence snapshot that started the run. Its connector
observation and semantic fact binding must both occur later in stream order, and
the observation occurrence time must not precede capability completion. The
latest eligible fact controls the assessment: a later contradictory value makes
the result pending with `VALUE_MISMATCH`.

This endpoint is intentionally read-only. `ACCEPTED` does not append a run
event, freeze the evidence, or close the case.

## Accept outcome proof

Call
`POST /internal/v1/tenants/{tenantId}/resolution-runs/{runId}/outcome-proof-acceptances`
to make a currently accepted assessment durable. Pending evidence returns
`409`; the command is valid only while the run is in `VERIFYING`.

The command locks run state, reassesses the pinned condition, and freezes the
exact case version, observation, and typed fact as run event sequence two. It
then advances the run and case to `VERIFIED_RESOLVED` in the same transaction.
The case closure is the event immediately following the assessed case version;
if evidence arrives concurrently, optimistic append rolls everything back and
the caller must reassess.

First acceptance returns `201`. Replay returns `200` with the original event
and never appends another case closure. Database foreign keys bind the durable
acceptance to the same tenant, run, case snapshot, observation, fact, and value.
Automated retries, compensation, and proof conditions beyond
`account.access.state` remain separate capabilities.

## Retry a failed attempt

Call
`POST /internal/v1/tenants/{tenantId}/resolution-runs/{runId}/retries` with the
current quoted case version in `If-Match` and a valid human bearer token. The
authenticated actor must have a current tenant-wide `RESOLVER` attestation.
Requester authority, expired evidence, and an identity registered only in
another tenant grant nothing. Only an `ACTION_FAILED` run at event sequence one
can start a retry. The first call returns `201` and the successor location;
replay returns the same successor with `200`.

A retry is a new immutable run, not a second receipt on the failed run. The
transaction freezes the current resolver evidence, case, and policy plan,
inserts attempt `n + 1`, appends an actor-attributed `RETRY_STARTED` to attempt
`n`, and marks the predecessor `SUPERSEDED`.
The contract revision, step, and capability must remain identical; a changed
operation requires later replanning semantics instead of being called a retry.

The successor copies no approval, grant, consumption, receipt, or provider
idempotency key. It starts again in its policy-derived requirement state. This
authenticated internal command neither schedules nor invokes work. Retry
eligibility, backoff, attempt limits,
compensation, and terminal escalation remain separate policy capabilities.

For `WAITING_FOR_APPROVAL`, the separate [approval request](approvals.md) API
can append a bounded human-authority request without granting that authority.
