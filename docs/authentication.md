# Human authentication

> **TL;DR:** API callers can resolve a human actor from a verified bearer JWT.
> An optional confidential OIDC BFF gives the workbench an opaque server
> session instead of browser-visible provider tokens. Both paths map the exact
> trusted issuer and opaque subject to an actor inside the requested tenant.

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
and browser claim routes return a stable `503`. The initial session store is
process-local, so restarts sign users out and multi-instance deployment is not
supported yet. Logout, provider revocation, and other mutating BFF routes are
deferred. See
[ADR 0036](decisions/0036-establish-confidential-browser-session-boundary.md)
and [ADR 0037](decisions/0037-expose-resolver-inbox-through-browser-session.md).
