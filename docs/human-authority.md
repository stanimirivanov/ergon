# Human authority evidence

> **TL;DR:** Ergon can register an opaque external human identity and record an
> immutable, expiring attestation that the actor is a requester for one case or
> a resolver for one tenant. A verified JWT can resolve the current actor, but
> evidence is still neither authentication nor an approval decision.

## Identity and evidence

Register an actor with
`POST /internal/v1/tenants/{tenantId}/human-actors`; retrieve it from the
returned location. The binding consists only of an Ergon UUID, stable identity-
provider name, opaque provider-local subject, application registration time,
and database recording time. Subjects must not contain credentials or bearer
tokens. The same provider/subject pair maps to only one actor per tenant.

Record evidence with
`POST /internal/v1/tenants/{tenantId}/human-actors/{actorId}/approval-authority-evidence`.
`REQUESTER` evidence must name a case in the same tenant. `RESOLVER` evidence is
tenant-wide and must omit `caseId`. The source provider and opaque reference
retain attribution to the external attestation.

The source supplies `expiresAt`; Ergon records `attestedAt` from its own clock
and rejects evidence that is already expired or lasts more than 24 hours.
`CURRENT` and `EXPIRED` are clock-derived using a half-open interval, so no
expiry job mutates the record. Actor bindings and attestations are append-only.

These internal endpoints are ingestion boundaries, not proof that the HTTP
caller is the actor or a trusted identity provider. The separate
[authentication boundary](authentication.md) resolves a verified issuer and
subject to a tenant actor. A decision may cite evidence only while it is current
and correctly scoped, as described in [approvals.md](approvals.md). Revocation
and capability authorization remain separate. Opaque subjects and evidence are
retained as audit history for the tenant lifetime; tenant deletion and a
broader privacy-retention policy are not implemented in this slice.
