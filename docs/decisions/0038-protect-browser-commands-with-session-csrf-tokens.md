# 0038. Protect browser commands with session CSRF tokens

- Status: proposed
- Date: 2026-09-25
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Protect confidential-BFF commands with Spring Security's session-bound CSRF
token. An authenticated browser obtains an opaque token and required header
name from a no-store resource, keeps the token in memory, and sends it with the
browser claim command. Missing or invalid tokens fail before application code.

## Context

The BFF uses an HttpOnly session cookie so provider tokens never enter browser
code. That cookie is attached automatically by the browser, which means a
cross-site page could attempt a state-changing request unless commands require
an independent value that another origin cannot read or place in a custom
header. `SameSite=Lax` reduces exposure but is required for the OIDC callback
and is not the sole command authorization mechanism.

The first browser mutation claims follow-up work. Its existing application use
case already resolves current authority, serializes competitors, returns the
original claim for a same-resolver replay, and rejects a competing owner. The
BFF should reuse those semantics rather than create browser-specific ownership
policy.

## Decision

Expose authenticated `GET /bff/v1/csrf`. It returns the opaque token
representation and header name supplied by Spring Security. The response is
covered by no-store security headers. Browser code must keep the value in
memory only, treat it as opaque, and fetch a new value after login, session
replacement, or an invalid-CSRF response.

Keep CSRF protection enabled for the stateful BFF filter chain. Every unsafe
BFF method must pass Spring Security's CSRF filter before controller dispatch.
An authenticated request with a missing, stale, or invalid token returns
`403 invalid-browser-csrf-token`. A request without an authenticated session
continues to return `401 browser-authentication-required` even when no CSRF
token is present, so session recovery remains unambiguous.

Add
`POST /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims`.
Resolve the OIDC identity to the tenant actor on the server and invoke the
existing claim use case. Return `201` for the first claim and `200` for a
same-resolver replay. The browser DTO contains claim ID, work-item ID, claim
instant, and recording instant only. It omits provider identity, resolver actor
identity, and authority-evidence attribution because those are not needed to
confirm the current actor's command.

Preserve the existing application failures: missing current resolver authority
is `403`, absent work is `404`, and a competing owner is `409`. The command
does not expose a browser claim `Location` until a browser-readable claim or
owned-work resource exists.

## Alternatives considered

### Rely only on `SameSite=Lax`

- Benefits: no token acquisition or header management.
- Costs and risks: cookie policy becomes the only anti-forgery boundary and is
  coupled to browser behavior needed by OIDC navigation.
- Reason not selected: consequential commands need explicit request binding.

### Put the CSRF token in a JavaScript-readable cookie

- Benefits: conventional double-submit integration for some frameworks.
- Costs and risks: introduces another cookie contract and requires SPA-specific
  token-handler behavior to remain aligned with Spring Security masking.
- Reason not selected: a small authenticated JSON resource is explicit,
  no-store, testable end to end, and does not require persistent browser state.

### Disable CSRF because commands use JSON

- Benefits: fewer protocol steps.
- Costs and risks: content-type assumptions are brittle and future endpoints or
  browser behavior could silently weaken the boundary.
- Reason not selected: the BFF authenticates with an ambient cookie, so unsafe
  methods require CSRF protection independent of their current payload shape.

### Duplicate claim policy for the browser

- Benefits: the adapter could evolve separately.
- Costs and risks: authority, replay, locking, and conflict semantics could
  diverge from bearer clients.
- Reason not selected: browser claiming has no distinct domain policy.

## Consequences

### Positive

Browser commands now have a tested anti-forgery contract in addition to the
HttpOnly session and SameSite cookie. Authentication, CSRF, tenant actor
resolution, current authority, and competing ownership fail at distinct
boundaries with stable problems. Provider tokens and authority evidence remain
outside JavaScript.

### Negative

The UI must acquire and retain an ephemeral token, attach its header to unsafe
requests, and recover when it becomes invalid. A distributed session store or
session-affinity design must preserve CSRF state with the authenticated session.

### Neutral or follow-up

This decision does not add claim controls to the workbench, a browser owned-work
view, item details, release, reassignment, or completion. Future BFF mutations
reuse this CSRF contract but still require their own idempotency, concurrency,
authorization, and cache-invalidation decisions.

## Compatibility and migration

The routes are additive and exist only when browser sessions are enabled.
Existing bearer routes, provider integrations, and persisted data are
unchanged. Disabling browser sessions makes the token and claim routes return
the existing `503 browser-authentication-unavailable` problem. No SQL migration
is required.

## Security and operations

Production ingress must keep the workbench and BFF same-origin over HTTPS and
must not cache token or command responses. The token is not persisted in local
or session storage, logged, placed in a URL, or sent to another origin. A CSRF
token proves request origin binding; it does not replace actor resolution or
stored resolver authority.

## Validation

Docker-backed HTTP integration tests exercise real session token acquisition,
header submission, first claim, replay, conflict, authority failure, absence,
cross-tenant actor rejection, no-store headers, and DTO omission. Security
tests distinguish unauthenticated `401` from authenticated CSRF `403` before
the claim service is called. Disabled-mode tests keep every defined BFF route
fail-closed.
