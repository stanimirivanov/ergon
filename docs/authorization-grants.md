# Capability authorization grants

> **TL;DR:** While its request is still current, one `APPROVED` decision can
> produce one immutable grant for the run's exact case, policy revision, step,
> and capability. The grant expires with the request and still performs nothing.

Create a grant with
`POST /internal/v1/tenants/{tenantId}/approval-decisions/{decisionId}/authorization-grants`.
The command accepts no body: scope and time are server-derived from immutable
run, request, and decision records. A rejection, expired request, cross-tenant
decision, or second derivation is refused.

The database copies and foreign-key binds the approved outcome, request expiry,
and run scope into append-only authorization history. This preserves meaning if
contracts or policy later change. The grant ID is an audit identity, not a bearer
credential.

Each grant is intended for one future atomic consumption. Consumption,
capability availability, caller authentication, connector credentials,
idempotency, invocation, receipts, and run transitions remain deliberately
outside this slice; no connector can yet execute from a grant.
