# Capability authorization grants

> **TL;DR:** While its request is still current, one `APPROVED` decision can
> produce one immutable grant for the run's exact case, policy revision, step,
> and capability. A current grant can be spent once only through that tenant's
> configured connector route; neither record performs the external action.

Create a grant with
`POST /internal/v1/tenants/{tenantId}/approval-decisions/{decisionId}/authorization-grants`.
The command accepts no body: scope and time are server-derived from immutable
run, request, and decision records. A rejection, expired request, cross-tenant
decision, or second derivation is refused.

The database copies and foreign-key binds the approved outcome, request expiry,
and run scope into append-only authorization history. This preserves meaning if
contracts or policy later change. The grant ID is an audit identity, not a bearer
credential.

## Consume once

Consume a grant with
`POST /internal/v1/tenants/{tenantId}/capability-authorization-grants/{grantId}/consumptions`.
The command accepts no body and locks the grant through insertion. It rejects an
expired, cross-tenant, unavailable, or previously consumed grant. Expiry is
checked before route lookup so stale authorization cannot probe tenant
configuration.

A `tenant_capability_routes` row configures one connector for a tenant and
capability. System vocabulary registration is insufficient, and a route for
another tenant is invisible. Routes are provisioned outside the runtime API in
this slice and are immutable; replacement, health, and revocation remain to be
designed.

The consumption copies and foreign-key binds the grant scope, validity window,
and selected connector. It is an irrevocable reservation from which later work
can resume after failure—not proof that the connector ran or succeeded.
Connector credentials, invocation, idempotency, receipts, and run transitions
remain deliberately outside this slice.
