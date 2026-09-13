# 0016. Model scoped human authority as expiring evidence

- Status: proposed
- Date: 2026-09-13
- Owners: Ergon maintainers

## Context

An approval grant must eventually identify a human and prove that the human
holds the authority requested by a resolution run. A generic tenant role is
insufficient: requester authority belongs to one case, whereas resolver
authority belongs to a tenant. Neither an actor identifier supplied by a
channel nor an approval-request response proves either fact.

## Decision

Bind each tenant-scoped human actor to one identity-provider name and opaque,
provider-local subject. Keep the binding immutable and unique within the
tenant. Do not store display names, email addresses, credentials, or tokens.

Record authority as an immutable external attestation with source attribution
and a half-open validity interval. `REQUESTER` requires a tenant-safe foreign
key to one case; `RESOLVER` is tenant-wide and requires no case. The application
assigns `attestedAt`, the source supplies the exclusive `expiresAt`, and both
the domain and database cap lifetime at 24 hours. Derive `CURRENT` or `EXPIRED`
from the clock instead of mutating status.

Treat the internal HTTP API only as an evidence-ingestion boundary. Recording
through it does not authenticate the caller, establish provider trust, approve
a request, or authorize execution. Those checks must precede any use of this
evidence in a later approval-grant use case.

## Alternatives

A tenant-wide `REQUESTER` role was rejected because it could approve actions
for unrelated cases. Embedding an email address in approval records was
rejected because addresses are mutable personal attributes, not stable
identities. Permanent mutable role assignments were rejected because their
current state would erase the attestation and expiry used by an earlier
decision. Treating these endpoints as authentication was rejected because a
request body cannot prove who sent it.

## Consequences

Later approval decisions can match a stable actor to a current, correctly
scoped authority attestation while retaining historical source and timing.
Tenant keys and foreign keys prevent cross-tenant actor and case associations.

This slice does not configure trusted issuers, authenticate callers, revoke
evidence early, accept decisions, create grants, or advance runs. Opaque
subjects and attestations are retained for the tenant lifetime; tenant deletion
and general privacy retention require a separate design before production use.

## Verification

Domain tests cover identity normalization, role scope, exclusive expiry, and
maximum lifetime. PostgreSQL-backed API tests cover registration, duplicate
identity rejection, requester and resolver attestations, missing and cross-
tenant resources, invalid scope and lifetime, immutable storage, and OpenAPI
publication. Existing migration tests exercise both empty and supported upgrade
paths.
