# ADR 0081: Retire local browser sessions

- Status: Accepted
- Date: 2026-10-28
- Milestone: M03 - Policy and Human Authority
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR27 supplies the M03 prerequisite for explicit sign-out in both apps.
Requester and workbench are separate origins/processes with fixed confidential
clients. A safe local endpoint must retire session credentials without promising
provider revocation, canceling accepted work or transferring durable ownership.
Spring's logout filter precedes route authorization; a route allowlist alone
cannot restrict its destructive behavior to the configured client.

## Decision

Expose only POST `/bff/logout` for an authenticated OAuth2 session with an OIDC principal belonging
to the deployment's fixed registration and an existing HTTP session. The preceding
session-bound CSRF filter remains mandatory. No tenant actor or current role lookup
is needed to relinquish the local session. Missing authentication returns the
existing 401 problem; authenticated foreign clients and non-OIDC identities are
denied. Missing/foreign CSRF retains the existing 401/403 problem behavior.

Use Spring Security's logout lifecycle for security-context, HTTP-session and CSRF
cleanup. Store browser authorized clients in `HttpSessionOAuth2AuthorizedClientRepository`
so their tokens share that session's lifetime instead of a principal-wide service.
Invalidation removes the saved return path, login state and authorized clients.
Other local sessions, even for the same principal, retain their own credentials.

Return empty 204 with `Cache-Control: no-store` and no redirect. Expire only the
configured workbench session cookie or the fixed requester session cookie.
Select that name in composition from the same server configuration, never
from caller cookies or servlet metadata. Preserve Secure, HttpOnly, Path=/ and SameSite=Lax;
do not add Domain or Clear-Site-Data. The cookie defaults remain unchanged.

The logout matcher independently checks client/authentication/session before
activating. All nonmatching `/bff/logout` methods and legacy `/logout` requests
are denied. Workbench captures `/logout` in its browser chain so it cannot fall
through to a stateless API logout shortcut. No confirmation page is exposed.
When browser sessions are disabled, POST `/bff/logout` returns the existing 503
unavailable problem; requester deployment still cannot disable its boundary.

## Alternatives considered

### Controller-managed logout

Normal route authorization is convenient, but a custom controller can omit
security-context/CSRF cleanup. Reuse the existing [Spring logout lifecycle](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html)
with a narrowly guarded matcher rather than reproduce its handlers.

### Provider logout or broad browser-data clearing

These may support a future global sign-out contract, but require provider,
cross-origin and other-session semantics beyond local retirement. Clear-Site-Data
can affect unrelated data/cookies. Neither is necessary for this bounded endpoint.

## Consequences

### Positive

Both clients gain one small session-only contract without database, domain,
dependency or authority-algebra changes. Framework cleanup and isolated filter
tests cover both audiences without requiring a database or live provider.

### Negative

Local sign-out is not IdP/global logout. An explicit later login may reuse the
provider session. A lost response remains an uncertain observation, and parallel
requests can encounter expiration/CSRF rejection. No durable replay receipt exists.

### Neutral or follow-up

M05-PR28 adds controls, private DOM/cache/request/CSRF retirement and executable
guides after this backend is reviewed and merged. A browser must not infer that
logout rolled back an accepted command or immediately released control leases.

## Compatibility and migration

Additive endpoint; no migration or new flag. Existing process-local sessions and
the two cookie defaults remain. Browser authorized-client storage becomes
session-scoped; process restart already retires old in-memory sessions/material.
Deploy backend before UI. Rollback removes sign-out support and requires users
to close private app state or expire local sessions; do not restore a global
principal-wide token deletion as a substitute. No persistent session store or
rolling token migration is introduced.

## Security and operations

The session cookie selects the local resource, never a caller-supplied tenant,
actor or registration. Provider calls and token revocation do not occur. In-flight
reads/writes that already obtained authority may finish; committed records,
claims, supervision and lease expiry remain governed by their own contracts.
Avoid logging cookies, CSRF values or provider credentials. No tokens appear in
responses. UI sign-out must be explicit and cannot rely on navigation alone.

## Validation

Isolated MVC tests run the real security filter chain for each audience: valid
retirement/cookie attributes, another session's continued access/client material,
rejected stale CSRF, missing/foreign CSRF, wrong identity/client, GET/legacy denial
and no redirects. Disabled-controller tests cover 503. Local policy, compilation,
formatting, static analysis and focused units precede delivery; CI owns the heavy
reactor/PostgreSQL gate. No live two-origin/IdP or UI-guide acceptance is claimed.
