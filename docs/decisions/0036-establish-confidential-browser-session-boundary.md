# 0036. Establish a confidential browser session boundary

- Status: proposed
- Date: 2026-09-21
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Put OIDC tokens behind a same-origin backend-for-frontend (BFF). The workbench
receives only an opaque, secure session cookie and a tenant-scoped actor view;
it never stores access, refresh, or ID tokens in browser code.

## Context

The resolver console needs interactive human authentication before it can call
tenant-scoped APIs. Ergon already trusts one exact JWT issuer and maps its
opaque subject to a registered tenant actor, but that resource-server boundary
does not define how a browser should acquire or retain credentials.

A direct single-page-application OAuth client would put tokens and refresh
behavior in browser code. [RFC 10017](https://www.rfc-editor.org/rfc/rfc10017)
ranks a BFF as the strongest of its evaluated browser application patterns and
strongly recommends it for applications that handle business or sensitive
data. Human follow-up can expose customer evidence and authorize consequential
work, so its browser boundary should not begin with the least isolated token
model.

This choice affects trust, deployment topology, configuration, cookies, and
both the control-plane and UI contracts. It therefore precedes UI integration.

## Decision

Add a confidential OIDC client to the control plane under the fixed
registration ID `ergon-workbench`. It uses the authorization-code grant,
`client_secret_basic`, the `openid` scope, and PKCE. The OIDC issuer must exactly
match the issuer already mapped by `HumanJwtTrust`; authentication and Ergon
authority remain separate decisions.

Keep the authorization request, provider tokens, and post-login return path in
the server-side HTTP session. The browser receives an HttpOnly, Secure,
host-only session cookie. Use `SameSite=Lax` because the authorization response
returns through a cross-site top-level navigation; `Strict` would suppress the
initiating session cookie. Sessions expire after 30 minutes by default.

Expose a versioned, tenant-scoped session resource at
`GET /bff/v1/tenants/{tenantId}/session`. It resolves the verified OIDC issuer
and subject through the existing actor registry and returns actor identity and
registration timestamps only. It never returns provider subjects or tokens.
An unauthenticated API request returns a stable `401` problem with a relative
`signInPath`; it is not converted into an HTML login redirect.

Start login at `GET /bff/login?returnTo=/tenants/{tenantId}`. Accept only that
canonical local path shape, retain it server-side, and consume it after
successful authentication. Do not use arbitrary request URLs or a client-sent
absolute URL as an OAuth success target.

Keep the existing bearer-token resource-server filter chain stateless and
separate from the stateful BFF chain. Browser sessions are disabled by default.
When disabled, the defined BFF login and session routes return a stable `503`
rather than silently behaving as public endpoints.

## Alternatives considered

### Public browser OAuth client with Authorization Code and PKCE

- Benefits: fewer backend routes and no application session store.
- Costs and risks: provider tokens and refresh behavior cross into the browser
  runtime, increasing the impact of script compromise and making API mediation
  a UI concern.
- Reason not selected: Ergon's resolver surface handles business data and
  consequential authority; the stronger token-isolation pattern is justified.

### Token-mediating backend

- Benefits: refresh tokens remain server-side while the browser can call APIs
  with short-lived access tokens.
- Costs and risks: bearer access tokens still enter browser code and require
  client-side lifecycle and leakage controls.
- Reason not selected: the UI has no requirement to possess a provider token.

### Reuse bearer JWTs in browser storage

- Benefits: reuses the current resource-server routes directly.
- Costs and risks: browser storage becomes a bearer-token boundary and login,
  refresh, logout, and compromise behavior remain undefined.
- Reason not selected: the existing JWT boundary authenticates API callers; it
  was not designed as a browser session architecture.

## Consequences

### Positive

Provider tokens remain outside browser code, session probing has a small stable
contract, actor lookup preserves tenant isolation, and OAuth redirects cannot
be used as an open redirect. The existing non-browser API authentication model
does not become stateful.

### Negative

The control plane now owns a confidential OAuth client and server-side session
state. Horizontal deployment will require a shared session store or session
affinity, and operations must protect and rotate the client secret. The BFF and
UI must share an origin in production, normally through ingress routing.

### Neutral or follow-up

This slice does not add logout, revocation, a distributed session store,
multi-provider selection, UI consumption, or a CSRF-token contract for future
mutating BFF routes. Those capabilities require their own observable behavior
and verification.

## Compatibility and migration

Browser sessions default to disabled, so existing deployments and stateless
bearer clients retain their behavior. Enabling the feature requires the fixed
Spring Security client registration and the existing issuer/provider trust
pair. Misconfigured registrations fail startup rather than weakening the
boundary.

Disable the feature to roll back. Existing in-memory sessions then cease to
serve the BFF endpoints and expire naturally; no persisted domain data or SQL
migration is involved.

## Security and operations

The client secret must come from deployment secret management and must never be
committed or exposed to the UI. Production ingress must terminate HTTPS,
preserve the host-only Secure cookie contract, route `/bff`, `/oauth2`, and
`/login/oauth2` to the control plane, and prevent caching of session responses.

The initial session repository is process-local. A restart invalidates sessions
and a multi-instance deployment is unsupported until a shared encrypted session
store or explicit affinity design is adopted. Authentication proves identity;
tenant registration and stored authority evidence still govern access and
actions.

## Validation

PostgreSQL-backed integration tests cover PKCE initiation, canonical local
return-path storage, unauthenticated problem responses, tenant-scoped actor
resolution, cross-tenant rejection, and omission of subjects and tokens. Unit
tests cover success-path restoration, one-time return-path consumption,
root fallback, and disabled-mode problems. The complete Maven reactor verifies
that the existing stateless bearer boundary remains compatible.

The browser interaction will be verified against a real OIDC provider when the
workbench consumes this contract; this backend slice uses deterministic test
registrations and verified mock OIDC principals.
