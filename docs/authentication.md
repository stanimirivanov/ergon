# Human authentication

## TL;DR

API callers can resolve a human actor from a verified bearer JWT. An optional
confidential OIDC BFF gives each separately deployed browser application an opaque
server session instead of provider tokens. Both paths map the exact trusted issuer
and opaque subject to an actor inside the requested tenant.

## Requester browser deployment

M05-PR15 adds `ERGON_BROWSER_SESSION_AUDIENCE=requester` alongside
`ERGON_BROWSER_SESSION_ENABLED=true`. The default audience remains `workbench`.
Deploy a **separate backend process and origin**, with a distinct confidential
IdP client registered as `ergon-requester`; use the same registration settings
shown below, replacing `ergon-workbench` with `ergon-requester` and supplying its
own client ID/secret. Restrict its IdP callback to
`https://<requester-origin>/login/oauth2/code/ergon-requester`. Unknown audiences,
missing selected registrations and issuer/client-policy mismatches fail startup.
Requester mode also refuses startup with browser sessions disabled: stop that
deployment to disable access, never fall back to legacy API security. Default
disabled workbench behavior is unchanged. Never share session stores or repurpose
a running workbench origin.

This deployment reuses `/bff/login?returnTo=/tenants/{tenantId}`, the tenant
`session` probe and `/bff/v1/csrf`, with unchanged sanitized/no-store DTOs. Login
always selects its fixed client and PKCE; only that client's callbacks are
accepted. Session/CSRF reads reject other-client authentication. The cookie is
`__Host-Http-Ergon-Requester-Session`, retaining Secure, HttpOnly, Path=/,
SameSite=Lax and no Domain. OIDC secrets and provider tokens remain server-side.

The explicit requester-owned [case progress](requester-case-progress.md) GET is
also allowed; it requires a separate durable case/requester binding, not approval
evidence. Its DTO has no private observations or runtime details.

Only those GET routes are allowed. Resolver, bearer, internal, generated login,
implicit logout and all unspecified routes are denied even to authenticated
requesters. Do not route the requester origin to the workbench backend. Use
process/container health checks; HTTP health/docs routes are not public here.
Tenant actor registration proves identity only: it grants no case ownership,
intake or resolver authority. Later requester slices must
explicitly extend the allowlist and enforce their own application authorization.

The browser boundary itself requires no migration; requester progress additionally
requires the ownership migration described in its guide. Roll back by stopping the requester deployment and
retiring its origin's cookie, leaving the workbench unchanged. Logout remains
M05-PR27. Mock OIDC tests plus a real embedded-server cookie check do not replace
live two-origin/IdP acceptance. See [ADR 0068](decisions/0068-isolate-requester-browser-deployments.md).

## Bearer API authentication

Set `ERGON_HUMAN_JWT_ISSUER_URI` to an OpenID Connect issuer that publishes
discovery metadata and signing keys. Set `ERGON_HUMAN_IDENTITY_PROVIDER` to the
stable provider name used when registering actors. Both values are required
together; with neither configured, the protected endpoint rejects bearer
tokens. Issuer discovery is lazy, but the issuer, JWT signature, time claims,
and subject are validated before controller code receives the token.

The issuer mapping is exact and intentionally single-provider for this slice.
The token's `sub` is treated as opaque and matched with the mapped provider in
`human_actors`; email, display name, roles, and client-supplied actor IDs are not
identity. A missing binding returns `403` without revealing whether it exists
in another tenant. Missing or invalid bearer credentials return `401`.

Only current-actor resolution is protected here. Existing case and internal
ingestion APIs retain their current exposure until their caller and service
authentication models are designed. Authentication does not confer approval
authority: a later decision must still match current, scoped authority evidence
to the authenticated actor and approval request.

The run-supervisor assignment command is a narrower machine-token exception
to those development-only internal routes. It requires a bearer token from
the configured issuer with audience `ergon-run-supervision`, scope
`ergon.run-supervision.assign`, an `ergon_tenant_id` claim matching the path,
and a nonblank `sub` recorded as the assigning machine subject. The identity
provider must reserve that audience and scope for tenant-scoped dispatch
clients; do not issue them to browser or ordinary human clients. The request
body may name a proposed resolver but cannot attest their
authority. Assignment additionally checks current tenant-wide resolver
evidence. Without a configured issuer, assignment fails closed. See
[resolution runs](runs.md#assign-and-inspect-active-runs).

## Browser session boundary

Set `ERGON_BROWSER_SESSION_ENABLED=true` only after configuring Spring
Security's OAuth client registration named `ergon-workbench`. The registration
must use the authorization-code grant, `client_secret_basic`, PKCE, and the
`openid` scope. Its provider issuer must exactly equal
`ERGON_HUMAN_JWT_ISSUER_URI`; its client secret belongs in deployment secret
management.

The equivalent Spring configuration shape is:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          ergon-workbench:
            provider: ergon-identity
            client-id: ${ERGON_OIDC_CLIENT_ID}
            client-secret: ${ERGON_OIDC_CLIENT_SECRET}
            client-authentication-method: client_secret_basic
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid
        provider:
          ergon-identity:
            issuer-uri: ${ERGON_HUMAN_JWT_ISSUER_URI}
```

The login sequence is:

1. Probe `GET /bff/v1/tenants/{tenantId}/session` with same-origin cookies.
2. On its `401` problem, append a canonical `/tenants/{tenantId}` return path
   to the supplied `/bff/login` sign-in path.
3. Follow the local redirect into the OIDC authorization flow.
4. After callback, probe the session resource again. It returns the Ergon actor
   view and never a provider subject or token.

An authenticated workbench can then read the shared resolver inbox from
`GET /bff/v1/tenants/{tenantId}/human-follow-ups`. The BFF resolves the same
verified session to the tenant actor; JavaScript does not supply an actor or
provider identity. See [human follow-up](human-follow-up.md#browser-resolver-inbox)
for the response, filtering, and pagination contract.

Before an unsafe BFF request, fetch authenticated `GET /bff/v1/csrf`. Keep its
opaque `token` in memory and send it using the returned `headerName`. Never put
the token in a URL, persistent browser storage, logs, or a request to another
origin. Fetch a replacement after login, session replacement, or
`403 invalid-browser-csrf-token`. Missing authentication continues to return
the normal `401 browser-authentication-required` problem even when the CSRF
token is missing, while an authenticated invalid token fails before controller
dispatch. See [ADR 0038](decisions/0038-protect-browser-commands-with-session-csrf-tokens.md).

The cookie is HttpOnly, Secure, host-only, `SameSite=Lax`, and expires with the
30-minute server session. Lax is deliberate: the OIDC callback is a top-level
cross-site navigation and needs the initiating session. Production must expose
the UI and BFF through one HTTPS origin. The Secure setting is not configurable;
local development should use `localhost`, which browsers treat as a secure
cookie context, or local HTTPS.

When browser sessions are disabled, the login, session, CSRF, browser inbox,
assigned-run reads, both browser claim routes, and browser release return a
stable `503`. The
initial session store is process-local, so restarts sign users out and
multi-instance deployment is not supported yet. Logout, provider revocation,
and other mutating BFF routes are deferred. See
[ADR 0036](decisions/0036-establish-confidential-browser-session-boundary.md)
and [ADR 0037](decisions/0037-expose-resolver-inbox-through-browser-session.md).
