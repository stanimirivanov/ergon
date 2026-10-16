# Supervisor-confirmed capability execution

## TL;DR

The current supervisor with current tenant-wide resolver evidence may explicitly
execute the existing approved pinned step. Admission commits before connector
I/O. Repeating a command never dispatches again: recovery is a separate explicit
attempt on the same reservation. Connector success reaches `VERIFYING`, not
verified case closure. Commands default off. See [ADR 0053](decisions/0053-confirm-supervisor-capability-execution.md).

## Review and confirm

Read `GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/execution-review`
using the existing confidential session. Current supervisor and resolver gates
apply before private facts are read. The response contains:

- `supported`, `observedAt`, `supervisionRevision` and `runVersion`;
- latest `requestId` and `decisionId`, when present;
- `commandId` only if this actor originated the run's execution command; and
- observed `canExecute` and `canResume`, never durable authorization tokens.

An older backend may lack this route. Missing support must not be interpreted
as revision zero, permission to execute, or a reason to call an internal endpoint.
Route configuration is not proof that a connector is healthy or installed.

After explicit review/confirmation, send a fresh UUID and the exact reviewed
coordinates to `POST .../{runId}/execution-commands`:

```json
{
  "commandId": "c8422f71-e870-4d9b-8d55-0e4693769978",
  "expectedSupervisionRevision": 0,
  "expectedRunVersion": 0,
  "requestId": "0ccf19d7-2602-46eb-bd12-4a67292b99db",
  "decisionId": "84b58226-21b7-41a4-bb2a-2cc2b6365595"
}
```

Authentication supplies the actor; do not send actor IDs, tool arguments,
connector choices, grant IDs or consumption IDs. The command checks current
supervision/resolver authority, the unchanged epoch/version, the latest exact
APPROVED decision and its still-valid request window. It derives and consumes
authorization through the existing tenant route. No prerequisite is downgraded.
Existing internal authorization for the run conflicts rather than being silently
adopted. Admission and its first immutable attempt commit atomically with the
grant/consumption. SQL errors before that commit leave no admission or effect.

Use the [session-bound CSRF flow](authentication.md#browser-session-boundary)
for both command endpoints. Responses and known problems are `no-store`.

## Acknowledgements and uncertainty

The response carries `commandId`, acknowledged `attemptId`, `runId`, `status`
and `admittedRecordedAt`. Result fields are nullable until the historical
sequence-one event exists: `outcome`, `resultingState`, `runSequence`,
`completedAt` and `resultRecordedAt`. It never exposes actor/attestation,
grant/consumption, provider-operation reference, credentials or raw payloads.

| Status | Meaning | HTTP |
|:--|:--|:--|
| `INCOMPLETE` | Reservation exists but no terminal receipt is durable. The provider may already have acted. | `202` |
| `RESULT_RECORDED` | Terminal receipt exists, but its run projection is not yet recorded. | `202` |
| `COMPLETED` | The original sequence-one result is durable: `SUCCEEDED` → `VERIFYING`, or `FAILED` → `ACTION_FAILED`. | `200` |

These fields describe historical execution, not a live span or the run's later
current state. COMPLETED does not mean independently verified outcome. A lost
response can be rechecked by submitting the exact original command/attempt;
that acknowledgement performs no connector call, even while incomplete.
Never generate a new command ID to resolve uncertainty or automatically retry.

## Explicit recovery and handover

If a fresh review permits recovery, confirm a new attempt UUID at
`POST .../{runId}/execution-commands/{commandId}/attempts`:

```json
{
  "attemptId": "21aa4144-3218-4fbf-8b43-d29bc805b4c1",
  "expectedSupervisionRevision": 0,
  "expectedRunVersion": 0
}
```

Only the original actor who currently supervises the run with current resolver
evidence may admit a new attempt. Reuse the original command/reservation; do
not create a grant, extend expiry or choose a different connector. The original
consumption remains resumable after its approval window expires. A new explicit
attempt may invoke the provider with that same consumption idempotency key;
concurrent explicit attempts still rely on provider-enforced idempotency.
Repeated attempt IDs acknowledge only. Receipt-without-projection recovery skips
the connector and repairs the existing deterministic result projection.

A former supervisor with current resolver evidence may retrieve their own
historical acknowledgement but cannot admit recovery. An incoming actor cannot
adopt that command. A handback requires a new attempt with the new supervision
epoch, not resurrection of the original epoch. If the originator cannot return,
trusted operator recovery is required; this slice does not establish a public
production recovery API. Handover/authority expiry cannot cancel an effect
whose admission already committed. Expired authority also prevents new private
acknowledgement disclosure, without rolling back that effect.

## Failure contracts

- `401`: browser authentication is required; `403`: identity/CSRF failure.
- `404 assigned-resolution-run-not-found`: private absence or foreign command.
- `409 run-execution-conflict`: stale intent, completed new attempt, missing/
  rejected/expired prerequisites, absent route or existing authorization.
- `400 invalid-run-execution`: malformed or unsupported coordinates.
- `503 run-execution-disabled`: the separate command flag is off.
- `503 run-execution-unavailable`: persistence/invariant acknowledgement failure;
  the reservation or receipt may already be committed. Recheck the same intent.
- Browser sessions disabled: `503 browser-authentication-unavailable`.

The suffixes above use the `urn:ergon:problem:` namespace. Connector exceptions
produce no fabricated terminal outcome and expose no cause text. Database errors
are not disguised as connector failures. No autonomous retry/worker is added.

## Deployment and verification

Deploy V20261016120000 and upgraded code before setting
`ERGON_RUN_SUPERVISOR_EXECUTION_ENABLED=true`. Supporting unique constraints scan
and lock existing grants/consumptions: assess size, index/WAL disk headroom and
lock time. There is no backfill. Disable commands for application rollback and
retain all reservations and audit; older internal services remain compatible.
The separate handover rollout restrictions still apply.

Real PostgreSQL tests cover released-schema upgrade with existing authorization,
ordinary FK/immutability constraints, concurrency, rollback, receipt/projection
crash boundaries and session/CSRF behavior. A controllable local connector checks
that audit is committed and no transaction encloses I/O. These tests do not prove
a real IdP login, remote-provider idempotency, adapter timeouts or production
operator recovery. The dependent UI and executable guide are a separate PR after
backend review/merge; this backend delivery contains no UI or guide changes.
