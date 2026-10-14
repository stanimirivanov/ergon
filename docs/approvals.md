# Approval requests

## TL;DR

A current run supervisor may review approval through the confidential Console
and, when enabled and exactly authorized, confirm a human prerequisite. Requester
scope, expiry and supervision are rechecked; approval never executes a capability.

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

## Browser supervisor review and decision

The assigned Console's `approvalReview` supplies `status`, `observedAt`,
`canDecide`, a nullable request and a nullable sanitized decision. Lifecycle is
`NOT_REQUIRED`, `NOT_REQUESTED`, `PENDING`, `EXPIRED` or `DECIDED`; an immutable
decision remains DECIDED after expiry. Requests include identity, exact authority
and request/expiry/recording instants; decisions include identity, outcome and
decision/recording instants, never actor or authority evidence. Older servers
omit this member: omission is unsupported, not no request or implicit authority.

Confirm through
`POST /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/approval-requests/{requestId}/decisions`
with session CSRF and `{commandId, expectedSupervisionRevision, outcome}`.
The BFF resolves the actor. First application requires current supervisor,
current tenant resolver evidence, exact supervision revision, WAITING_FOR_APPROVAL,
latest unexpired request and current exact authority. REQUESTER evidence must
name this case; resolver evidence cannot substitute. Both outcomes use these gates.
Request creation/replacement remains the trusted internal operation above.

The first response is 201; exact historical replay is 200 with the original
persisted receipt: command/run/request identity, unchanged supervision revision,
decision identity/outcome and stored decision/recording instants. Replay requires
the originating actor's current resolver evidence, not current supervision or
still-current requester evidence; it records nothing new. Changed own intent
conflicts; foreign receipt/run/request absence is neutral 404. Stale epoch,
expiry, answered request or non-waiting state yields `409 run-approval-conflict`.
Missing exact authority yields `403 run-approval-authority-missing`. Responses
and known problems are no-store and omit private attribution or raw causes.

Deploy the additive migration and upgraded backend before enabling
`ERGON_RUN_SUPERVISOR_APPROVAL_ENABLED=true` (default false). Disabled commands
return `503 run-approval-disabled`; reads remain available with `canDecide=false`.
The browser never enables rollout. Disable commands for application rollback;
retain decisions and receipts. Existing bearer decisions remain compatible.
See [ADR 0049](decisions/0049-confirm-supervisor-scoped-approval-decisions.md).
