# Resolution runs

> **TL;DR:** Starting a run freezes the ready case version, contract revision,
> policy revision, first step, and effective safeguards in one immutable row.
> `WAITING_FOR_APPROVAL` is a requirement state, never execution permission.

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
capability checks. Neither state permits tool invocation.

The start row is not current execution state. Approval, authorization,
capability invocation, receipts, retries, verification, and terminal state will
be represented by later append-only run events and projections.

For `WAITING_FOR_APPROVAL`, the separate [approval request](approvals.md) API
can append a bounded human-authority request without granting that authority.
