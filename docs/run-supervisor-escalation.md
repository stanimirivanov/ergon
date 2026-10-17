# Supervisor-confirmed exhausted-run escalation

## TL;DR

A current supervisor with current tenant-wide RESOLVER evidence can explicitly
end exhausted ACTION_FAILED recovery and open one unassigned human follow-up.
The case is not closed, no connector runs and no third attempt is granted.
Commands default off. See [ADR 0055](decisions/0055-confirm-supervisor-exhausted-run-escalation.md).

## Review

Read `GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/escalation-review`
with the confidential session. Current supervisor/resolver gates apply even to
disabled and terminal review; unavailable/private context is neutral 404.

The no-store response contains `supported`, `observedAt`, `supervisionRevision`,
`runVersion`, `attemptNumber`, `retryPolicyRevision`, `maximumAttempts`,
`canEscalate`, `reason` and `followUpQueueKey`. Only
RETRY_ATTEMPT_LIMIT_REACHED permits confirmation. Other reasons are DISABLED,
RUN_NOT_FAILED and RETRY_BUDGET_AVAILABLE. The current policy permits two total
attempts including the initial run. Unsupported older servers may lack this
endpoint; never fall back to internal ingress.

Review is advisory, not an authority token. No new plan or raw later evidence
is disclosed. The destination is always server-selected access-restoration.

## Confirm and replay

`POST /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/escalation-commands`
requires session CSRF and this exact reviewed intent (UUID is illustrative):

```json
{
  "commandId": "d138d19f-936e-4f27-b751-f66caeb577fd",
  "expectedSupervisionRevision": 0,
  "expectedRunVersion": 1,
  "sourceAttemptNumber": 2,
  "retryPolicyRevision": "ergon.dev/policy/resolution-retry/v1",
  "maximumAttempts": 2,
  "reason": "RETRY_ATTEMPT_LIMIT_REACHED"
}
```

Actor, time, queue, work identity and recipient remain server-owned. Stale
supervision/state/policy/attempt/ceiling or an unrelated prior escalation
conflicts, without adopting its event or work. Runtime exhaustion is checked
again inside the atomic use case. An uncertain invocation must use its distinct
execution recovery contract, not this terminal failed-run command.

First application returns 201; exact replay returns 200 with the identical
stored receipt. Receipt fields are `commandId`, `runId`, `supervisionRevision`,
`expectedRunVersion`, `sourceAttemptNumber`, `retryPolicyRevision`,
`maximumAttempts`, `reason`, `escalationEventId`, `followUpWorkItemId`,
`followUpQueueKey`, `creationStatus`, `requestedAt` and `recordedAt`.
creationStatus is the immutable original OPEN fact, not current claiming/status.
No internal Location or private actor/authority/provider identity is returned.

Replay requires the original actor with current resolver evidence, not continued
supervision. It grants neither run access nor follow-up ownership. Fresh queue
discovery and any later claim/owned-case inspection use their separate current
gates. If the original POST never committed, explicit exact replay can first
apply it under current gates. Never automatically retry, substitute a new command
ID or restore private content from a historical receipt.

## Atomic effects and failures

One short transaction appends ESCALATION_REQUESTED, advances the run to ESCALATED,
opens one access-restoration work item and stores exact browser audit. It neither
changes case state nor claims/assigns work, notifies anyone, invokes a connector,
compensates or authorizes anything. Existing runtime/follow-up owners perform
their own writes. Competing handover/retry/escalation decisions serialize.

Stable no-store problems are `run-escalation-conflict` (409),
`run-escalation-disabled` and `run-escalation-unavailable` (503),
`invalid-run-escalation` (400), and neutral `assigned-resolution-run-not-found`
(404). Existing identity/session/CSRF problems retain their meaning. Disabled
browser sessions return browser-authentication-unavailable 503. Sanitized
unavailability is not evidence that a command failed to commit.

## Rollout and recovery

Deploy V20261017120000 and backend code before enabling
`ERGON_RUN_SUPERVISOR_ESCALATION_ENABLED=true`. Source-key UNIQUE/FK validation
scans events/work and takes write-conflicting locks. Assess table/index size,
lock windows, WAL, replication lag and free disk; no backfill occurs. Existing
internal writers and assignment readers remain compatible. Disable commands to
roll back behavior, retain all history and recover forward. Never undo escalation
or delete audit to repeat a command.

Review/merge the backend before implementing the dependent UI and executable
guide. Synthetic sessions and isolated source fixtures are not real OIDC,
provider or session-to-database browser certification. General assignment,
notifications, follow-up completion, leases and compensation remain out of scope.
