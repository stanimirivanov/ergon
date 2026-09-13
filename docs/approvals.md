# Approval requests

> **TL;DR:** A run in `WAITING_FOR_APPROVAL` can create one active, 15-minute
> request for its pinned `REQUESTER` or `RESOLVER` authority. The request is not
> an approval, contains no actor, and never authorizes execution.

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
24 hours. Callers cannot choose or extend the lifetime. Actor identity,
authentication, role evidence, approval or rejection decisions, revocation,
run-state transitions, and capability authorization remain separate behavior.
Human identities and role evidence are described in
[human-authority.md](human-authority.md); they are deliberately not interpreted
as approval here.
