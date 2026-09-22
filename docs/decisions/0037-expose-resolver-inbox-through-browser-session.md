# 0037. Expose the resolver inbox through the browser session

- Status: Proposed
- Date: 2026-09-21
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Expose the existing read-only resolver inbox through the confidential BFF. The
control plane resolves the authenticated OIDC session to a tenant actor and
reuses the application query, while the browser receives a dedicated,
token-free response contract rather than access to internal bearer routes.

## Context

The workbench can establish an opaque browser session, but its only BFF
resource is the current actor. Rendering actionable work would otherwise
require either exposing provider tokens to JavaScript or treating `/internal`
bearer routes as a production browser contract. Both would violate the session
boundary established by [ADR 0036](0036-establish-confidential-browser-session-boundary.md).

The application already enforces tenant scope, current resolver authority,
queue validation, bounded keyset pagination, and non-disclosure when authority
is absent. Duplicating those decisions for the browser would create divergent
authorization behavior.

## Decision

Add `GET /bff/v1/tenants/{tenantId}/human-follow-ups`. The route requires the
confidential OIDC browser session, resolves the verified issuer and subject
through the existing tenant actor registry, and calls the existing
`HumanFollowUpWorkItemQueryService`.

Keep a browser-specific request and response model. The response contains only
immutable work source, routing, lifecycle, and timestamp facts needed for an
inbox row. It never includes provider subjects, OAuth/OIDC tokens, authority
evidence, or a total count. The internal bearer response is not reused as a
browser compatibility type.

Preserve existing query semantics: optional exact queue filtering, a default
limit of 50 bounded to `1..100`, paired `afterOpenedAt` and `afterWorkItemId`
cursor components, and oldest-first ordering. An actor without current
tenant-wide resolver authority receives an empty page. Invalid page and queue
input retain the existing stable problem types.

The route returns the same fail-closed `401` sign-in contract as other BFF API
requests. When browser sessions are disabled, it returns the established `503`
browser-authentication-unavailable problem.

## Alternatives considered

### Call the internal bearer endpoint from the browser

- Benefits: no additional HTTP adapter.
- Costs and risks: provider access tokens enter browser code and an internal
  development boundary becomes an accidental public contract.
- Reason not selected: it defeats the confidential BFF design.

### Create a separate browser inbox use case

- Benefits: independent behavior could evolve quickly.
- Costs and risks: authorization, validation, filtering, and pagination could
  drift between adapters.
- Reason not selected: the browser has no distinct domain policy in this slice.

### Reuse internal wire DTOs

- Benefits: less mapping code.
- Costs and risks: either contract could expose or rename fields for the other
  audience unintentionally.
- Reason not selected: browser and internal protocols have different
  compatibility and disclosure responsibilities.

## Consequences

### Positive

The workbench can read real resolver work without handling provider tokens.
One application boundary continues to own authority and pagination semantics,
and browser/internal contracts can evolve independently.

### Negative

The control plane maintains a small second HTTP mapping for the same
application result. Response changes must be reviewed against the browser
compatibility boundary as well as the internal one.

### Neutral or follow-up

This decision does not expose item details, owned work, claiming, lifecycle
mutations, or case summaries. Mutating BFF routes must first define CSRF and
command replay behavior.

## Compatibility and migration

The route is additive and is absent unless browser sessions are enabled.
Existing bearer clients and persisted data are unchanged. Rollback removes the
browser route or disables browser sessions; no database migration is involved.

## Security and operations

The tenant path remains a scope selector, never a credential. Verified OIDC
identity is resolved server-side and stored resolver authority remains the
authorization source. Responses inherit Spring Security no-store headers.
Ingress must keep the UI and BFF same-origin over HTTPS as required by ADR
0036.

The empty result for missing resolver authority deliberately trades a detailed
denial for non-disclosure of tenant work. Authentication and actor-registration
failures remain explicit `401`/`403` problems because they disclose no work.

## Validation

Docker-backed HTTP integration tests verify session enforcement, tenant actor
resolution, exact query translation, browser response shape, no-store headers,
stable validation problems, cross-tenant non-disclosure, and disabled-mode
failure. The complete Maven reactor verifies compatibility with existing
bearer routes and application query behavior.
