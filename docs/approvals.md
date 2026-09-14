# Approval requests

> **TL;DR:** A run in `WAITING_FOR_APPROVAL` can create one active, 15-minute
> request. An authenticated actor with matching current authority evidence can
> answer it once. The immutable decision records audit evidence; a separate
> bounded grant may derive from approval but still does not execute anything.

## Request and inspect

Create a request with
`POST /internal/v1/tenants/{tenantId}/resolution-runs/{runId}/approval-requests`.
Retrieve its returned location with
`GET /internal/v1/tenants/{tenantId}/approval-requests/{requestId}`.

The response identifies the run, step, required authority, request and expiry
instants, database recording time, and current `PENDING` or `EXPIRED` status.
Status is evaluated from the application clock and is `EXPIRED` at the exact
`expiresAt` instant; it is not mutable database state and needs no expiry job.

Requests are immutable audit records. An active request causes `409 Conflict`
and the problem response identifies that request and its expiry. Once it
expires, another request may be appended while the old request remains
retrievable. Concurrent creation is serialized on the immutable run row.

The current built-in lifetime is 15 minutes and the domain/database maximum is
24 hours. Callers cannot choose or extend the lifetime.

## Decide once

Submit `APPROVED` or `REJECTED` to
`POST /api/v1/tenants/{tenantId}/approval-requests/{requestId}/decision` with a
bearer JWT. Ergon derives the actor only from the verified issuer and subject;
the body cannot select an actor, evidence record, case, run, or decision time.

The request row is locked while Ergon verifies that it is still current, finds
the immutable run and case, and selects current authority evidence for the
actor. `REQUESTER` evidence must match the run's case; `RESOLVER` evidence must
be tenant-wide. Validity uses the same application-clock instant for the
request, evidence, and recorded decision. If several attestations match, the
latest `attestedAt` and then evidence ID determine the selected audit source.

Only one decision can answer a request. Retries return the existing decision ID
as `409 Conflict`; records cannot be updated or deleted. Both outcomes require
matching authority. An `APPROVED` decision is a human prerequisite, not
authorization by itself. A separate grant can be derived only while the request
remains current; see [authorization-grants.md](authorization-grants.md). Human
identity and evidence semantics are in [human-authority.md](human-authority.md),
and bearer identity resolution is in [authentication.md](authentication.md).
