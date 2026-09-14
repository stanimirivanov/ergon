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

For `WAITING_FOR_APPROVAL`, the separate [approval request](approvals.md) API
can append a bounded human-authority request without granting that authority.
