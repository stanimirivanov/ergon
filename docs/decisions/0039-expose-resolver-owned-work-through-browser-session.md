# 0039. Expose resolver-owned work through the browser session

- Status: proposed
- Date: 2026-09-25
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Expose the existing resolver-owned active-work query through the confidential
BFF. Resolve the actor from the OIDC session, preserve application-owned
authority and keyset semantics, and return a browser-specific representation
without provider identity or authority-evidence attribution.

## Context

Claiming removes work from the shared inbox. The control plane already lets a
bearer-authenticated resolver recover their active claims, but that internal
route is not a supported browser contract. Without a BFF resource, the
workbench cannot continue work it just claimed without handling a provider
token or promoting an internal development boundary.

The existing application query already limits results to `OPEN` work owned by
the authenticated actor under current tenant-wide resolver authority. It owns
oldest-claim-first ordering, bounded pagination, paired cursors, and empty-page
non-disclosure. The browser has no distinct policy that justifies duplicating
those decisions.

## Decision

Add `GET /bff/v1/tenants/{tenantId}/human-follow-ups/owned`. Resolve the
verified OIDC session to the tenant actor and call
`HumanFollowUpClaimService.listOwned`.

Preserve the existing default limit of 50, valid range of `1..100`, paired
`afterClaimedAt` and `afterClaimId` cursor, and oldest-claim-first ordering.
Malformed pagination returns the existing
`invalid-resolver-owned-human-follow-up-page` problem. Missing current resolver
authority remains an empty page and does not disclose whether claims exist.

Return separate browser `workItem` and `claim` objects so creation and ownership
facts keep their meanings. The claim contains its identity, work-item identity,
claim instant, and recording instant. It omits resolver actor and authority
evidence identifiers because the page is already scoped to the current actor.
Provider subjects and tokens never enter the representation.

The route inherits the established browser-session `401`, actor-binding `403`,
no-store response headers, and disabled-mode `503` behavior. It is read-only
and does not require a CSRF token.

## Alternatives considered

### Call the internal bearer route from the workbench

- Benefits: no additional HTTP mapping.
- Costs and risks: provider credentials enter browser code and an internal
  boundary becomes a public compatibility contract.
- Reason not selected: it violates the confidential BFF architecture.

### Add the owned row to the claim response only

- Benefits: immediate confirmation after claiming.
- Costs and risks: reload and recovery remain impossible, and ownership state
  becomes coupled to one command response.
- Reason not selected: active work is a queryable resource, not transient UI
  state.

### Reuse the internal response DTO

- Benefits: less mapping code.
- Costs and risks: it exposes resolver identity and authority-evidence IDs that
  browser rendering does not need.
- Reason not selected: the browser has a narrower disclosure boundary.

## Consequences

### Positive

The workbench can recover active claims after navigation or reload without
provider credentials. Internal and browser adapters continue to share one
application authorization and pagination policy while maintaining independent
wire contracts.

### Negative

The control plane maintains another small browser mapping. Changes to owned
work must be reviewed against both browser and internal compatibility needs.

### Neutral or follow-up

This decision does not add the owned-work UI, item detail, case summary,
release, reassignment, completion, or supervisor workload views.

## Compatibility and migration

The route is additive and exists only when browser sessions are enabled.
Existing bearer routes and persisted data are unchanged. Rollback removes the
route or disables browser sessions. No SQL migration is required.

## Security and operations

The tenant path remains navigation context, not authority. The verified session
determines the actor, and persisted current resolver evidence remains the
authorization source. Responses are no-store and contain no provider subject,
token, resolver actor identifier, or authority-evidence identifier. Production
ingress remains same-origin HTTPS as required by ADR 0036.

## Validation

Docker-backed HTTP integration tests verify session enforcement, tenant actor
resolution, exact pagination translation, empty-page non-disclosure, browser
response shape, omitted identity and authority attribution, no-store headers,
stable invalid-page behavior, cross-tenant rejection, and disabled-mode
failure. The full Maven reactor protects existing internal and persistence
behavior.
