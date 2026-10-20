# Recorded gateway-attempt history

## TL;DR

Current supervisors may inspect recorded gateway preparations and measured
returns/exceptions. This is neither a live trace nor complete historical
coverage. Preparation does not prove dispatch; UNKNOWN does not prove failure.
No read grants execution or recovery authority.

## Browser contract

`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/invocation-history`
uses the confidential session. Current exact-run supervision and current
tenant-wide RESOLVER evidence are checked before history lookup. Terminal
runs, prior supervision epochs and internal execution remain eligible. No
browser control lease, original actor or enabled command flag is required.

The response contains `observedAt`, `supervisionRevision`, `entries` and
`truncated`. Each entry supplies pinned `stepId`, `capability`, `connector`,
`preparedAt`, `recordedAt` and nullable `completion`. A completion supplies
`outcome`, `completedAt`, `recordedAt` and `timing`: source
`ergon.dev/measurement/connector-call/v1`, `startedAt` and exact decimal-string
`elapsedNanoseconds`. Never coerce large nanoseconds to JavaScript Number.

## Interpret facts narrowly

- Preparation committed before possible I/O. A process could stop before dispatch.
- Null completion means no completion recorded, not live/in-flight work or no effect.
- SUCCEEDED/FAILED are gateway-reported results, not independent outcome proof.
- UNKNOWN records a thrown gateway call; provider effects remain unresolved.
- Durations use monotonic measurements, never UTC subtraction or inferred cost.
- Concurrent callers retain separate samples; the existing winning terminal
  receipt remains authoritative for result projection.
- Receipt replay creates no new preparation, call, timing or backfill.

Results contain the first 100 preparations ordered by database recording time,
then private identity; one extra row detects truncation in the statement snapshot.
Clock correction and concurrency mean this is not causal order or a stable
cross-read prefix. Historical and older-writer calls are not reconstructed.
Empty history therefore never proves that no provider operation occurred.

## Failures and privacy

Missing, foreign, unassigned, former-supervisor and expired-resolver access
share no-store `404 assigned-resolution-run-not-found` before history access.
Invalid coordinates use existing `400 invalid-run-execution`; storage/invariant
failure uses cause-free `503 run-execution-unavailable`. Browser-disabled
deployments return no-store `503 browser-authentication-unavailable`.
No actor, authority, grant, consumption, command/attempt replay coordinate,
session/fence, provider operation, raw payload or exception text is disclosed.

## Persistence and rollout

Install V20261020120000 before new writers/readers. Preparation and completion
are separate append-only tables with tenant-safe references. Terminal
completion and receipt recording commit together. Uncertainty completion
commits separately before rethrow; audit failures propagate, not masquerade as
connector failure. Connector I/O remains outside database transactions.

The consumption index scans existing rows and blocks writes while building;
budget index/WAL space, lock time and retained audit growth. New tables start
empty. Older explicit-column writers remain compatible but unrepresented.
No backfill or feature-flag enablement occurs. Retain schema/audit on application
rollback; deleting an incomplete record cannot undo an external effect.

## Verification and follow-up

See [ADR 0060](decisions/0060-record-observed-gateway-attempt-history.md).
Application and PostgreSQL/HTTP tests cover measurement, uncertainty, replay,
concurrency, commit boundaries, integrity, scope, privacy and migration upgrade.
Synthetic sessions/local connectors do not establish live IdP or provider safety.
The dependent UI/user-guide slice follows backend review/merge. General spans,
multi-step execution, polling, billing, retention/deletion and new recovery
policies remain out of scope.
