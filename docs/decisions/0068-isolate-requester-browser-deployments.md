# ADR 0068: Isolate requester browser deployments

- Status: Accepted
- Date: 2026-10-24
- Milestone: M03 - Policy and Human Authority
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR15 needs a separately authenticated requester application before exposing
case progress. Reusing resolver sessions or relying on hidden UI controls would
cross a trust boundary. Existing internal routes are not a public security model.

## Decision

Select exactly one browser audience at deployment startup: `workbench` (default)
or `requester`. Reuse the confidential authorization-code/PKCE BFF and tenant actor
resolution, with fixed registration `ergon-requester` for the latter. Bind both
authorization initiation and callbacks to the selected registration. No browser
parameter selects an audience or grants authority.

Run requester and workbench on separate origins and processes with distinct IdP
client IDs and exact callback allowlists. Requester mode uses a distinct host-only
Secure/HttpOnly session cookie. Its security chain covers **all** paths, allowing
only GET login, the selected OIDC initiation/callback, tenant session and CSRF.
Session/CSRF reads additionally require authentication from the requester client.
Everything else is denied, including resolver, bearer and legacy internal APIs;
there is no fallthrough to the legacy permit-all chain or generated login/logout.

Identity remains the configured verified issuer and opaque subject. Registration
in a tenant grants neither case ownership nor resolver authority. Public session
and CSRF DTOs are unchanged and no-store; provider subjects/tokens stay server-side.

## Alternatives considered

One process with two browser clients would require independently namespaced
security contexts, authorization requests, cookies and logout. That complexity is
unnecessary for the agreed separate deployments. A shared resolver client/session
would conflate trust boundaries. A new identity framework adds no needed behavior;
reuse existing Spring Security without adding dependencies.

## Consequences

The requester origin fails closed even for an authenticated resolver identity.
Operations must provision and route two confidential clients/processes. Requester
health/API documentation routes are also denied by this public boundary; use
process/container health checks rather than exposing internal APIs. Process-local
sessions still preclude unsupported multi-instance deployment.

## Compatibility and migration

Existing disabled and workbench defaults retain their contracts. Workbench OIDC
initiation/callbacks now accept only its fixed client, not arbitrary registrations.
No SQL migration is needed. Deploy a separate requester process; do not repurpose
a live workbench origin or share session stores. Stop that process to roll back;
retire its cookies on the requester origin. No case or authority data is changed.

## Security and operations

Use HTTPS and ingress that preserves host-only cookie isolation, same-origin BFF
routing and no-store responses. Client secrets come from deployment secret storage.
Configure only the intended client's callback at the IdP; an IdP SSO session is
not an Ergon session. Missing/invalid registration or unknown audience
fails startup. Requester mode with sessions disabled also fails startup, and
never restores the legacy API chain. Stop the deployment to disable access;
default disabled workbench behavior is unchanged. Logout, provider revocation
and distributed sessions remain deferred.

## Validation

Docker-backed HTTP tests cover requester tenant binding, sanitized session data,
PKCE, canonical returns, session CSRF, cross-client and denied-route behavior.
A real embedded server checks the emitted requester cookie. Focused configuration
tests reject invalid audience, missing client, mismatched issuer and unsafe client
configuration. Full reactor verification retains existing workbench/bearer coverage.
These deterministic tests do not prove live IdP exchange or two-origin deployment;
that acceptance gate remains open for the first requester UI slice.
