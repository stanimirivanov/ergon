# 0017. Authenticate human actors from verified JWT subjects

- Status: proposed
- Date: 2026-09-13
- Owners: Ergon maintainers

## Context

An approval decision must eventually identify its human caller without trusting
an actor ID, email address, or authority claim supplied in an HTTP body. Ergon
already binds an opaque external provider subject to a tenant actor, but the
existing internal registration API is not authentication. The authentication
boundary must also avoid exposing whether an identity belongs to another tenant.

## Decision

Protect current-actor resolution with a stateless OAuth 2.0 resource server.
Validate JWT signatures, time claims, and the exact configured issuer through
issuer discovery. Map that trusted issuer to one stable Ergon identity-provider
name and use the verified `sub` claim as the opaque provider subject. Resolve
the pair again under the tenant in the request path and return `403` for an
absent binding.

Require issuer URI and provider mapping together. With neither configured,
start the service but reject bearer tokens on the protected boundary. Defer
issuer discovery until a token is decoded so temporary provider unavailability
does not prevent application startup. Do not copy email, display name, roles,
or approval authority from token claims.

Protect only `GET /api/v1/tenants/{tenantId}/human-actor` in this slice.
Existing public and internal endpoints retain their current exposure until
their caller models and authorization policies are specified.

## Alternatives

Accepting `actorId` in an approval body was rejected because possession of an
identifier proves no identity. Mapping by email was rejected because email is
mutable and personally identifying. A tenant header was rejected because the
resource path already carries the tenant boundary and lookup must enforce it.
Eager issuer discovery was rejected because identity-provider downtime should
not make unrelated control-plane capabilities fail to start.

## Consequences

Later approval decisions can derive their actor from verified protocol claims
and then evaluate stored authority evidence separately. The same token cannot
resolve an actor registered only in another tenant. Initial configuration
supports one issuer-to-provider mapping; multiple issuers, key pinning beyond
standard discovery, service authentication, token revocation, and endpoint
authorization remain future security decisions.

## Verification

PostgreSQL-backed HTTP tests cover successful subject resolution, missing
credentials, exact issuer rejection, unregistered and cross-tenant identities,
and OpenAPI publication. The complete build continues to exercise the existing
unauthenticated endpoints so this slice does not silently broaden their access
contract.
