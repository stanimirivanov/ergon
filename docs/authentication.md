# Human authentication

> **TL;DR:** `GET /api/v1/tenants/{tenantId}/human-actor` validates a signed
> bearer JWT, maps its exact issuer to an Ergon identity-provider name, and
> resolves its opaque subject inside the requested tenant. It accepts no actor
> identity from the request body.

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
