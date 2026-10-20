# Supervisor-confirmed failed-run retry

## TL;DR

A current supervisor with current tenant-wide RESOLVER evidence may confirm a
policy-eligible fresh successor of ACTION_FAILED. Runtime retry, successor
supervision and exact command audit commit together. This is not uncertain
invocation recovery and does not execute, authorize, verify or close anything.
Commands default off. See [ADR 0054](decisions/0054-confirm-supervisor-retry-and-successor-supervision.md).

## Review contract

Read `GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/retry-review` with
the confidential session. Current supervision and resolver evidence gate private
reads, including disabled/terminal review. Unavailable/non-owned context is neutral 404.

The no-store response contains `supported`, `observedAt`, `supervisionRevision`,
`runVersion`, `attemptNumber`, `retryPolicyRevision`, `maximumAttempts`, `canRetry`,
nullable `reason` and nullable `plan`. The ceiling includes the initial attempt;
the current runtime permits two total attempts. `supported: false` means commands
are disabled. An older backend may lack the endpoint; never fall back to internal APIs.

Reasons are DISABLED, RUN_NOT_FAILED, ATTEMPT_LIMIT_REACHED, PLAN_NOT_READY,
PLAN_DENIED and OPERATION_CHANGED. Only null reason permits confirmation. An
eligible plan contains exact `caseStreamVersion`, `contractKey`, `contractRevision`,
`policyRevision`, `stepId`, `capability`, `effectiveRisk` and `requiredApproval`.
These are narrow planning coordinates, not raw post-pin observations. Disabled
review can retain an otherwise eligible plan but cannot confirm; ineligible
planning has no plan. Review reveals no unrelated successor or foreign command.

## Recover a visible successor

`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/retry-successor`
recovers the sole direct relationship from immutable runtime history, not a
browser-command receipt. Current exact predecessor supervision and tenant-wide
RESOLVER evidence gate history lookup. The same actor must independently have
current supervision and resolver evidence for the target, even after handover.
Both runs may be terminal; neither a control lease nor enabled retry commands
is required. Internal retries work once their successor has a trusted assignment.

The no-store response has exactly `observedAt`, `supervisionRevision` (source
epoch), and nullable `successor`. A visible successor contains `runId` and its
own `supervisionRevision`. Null means **no currently visible direct successor**:
absent retry, unassigned target and inaccessible target are indistinguishable.
It is not proof that no retry occurred. Hidden identity/existence, actors,
commands, retry-event metadata and successor details are not disclosed.

Authority checks use one observation instant, not an atomic cross-query snapshot
or continuous revocation guarantee. Following the link always needs fresh exact
target reads. The link grants no acknowledgement, assignment, lease or execution
authority and does not change uncertain command intent. Existing no-store 404,
400 and cause-free `run-retry-unavailable` 503 apply; a disabled browser-session
deployment returns browser-authentication-unavailable 503. Storage failure is
not successful absence. No migration or flag change is needed. See
[ADR 0059](decisions/0059-recover-independently-visible-retry-successors.md).

## Confirm and replay

`POST /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/retry-commands` requires
session CSRF and this body (coordinates are illustrative):

```json
{
  "commandId": "d138d19f-936e-4f27-b751-f66caeb577fd",
  "expectedSupervisionRevision": 0,
  "expectedRunVersion": 1,
  "retryPolicyRevision": "ergon.dev/policy/resolution-retry/v1",
  "maximumAttempts": 2,
  "plan": {
    "caseStreamVersion": 4,
    "contractKey": "restore-workspace-access",
    "contractRevision": 1,
    "policyRevision": "ergon.dev/policy/access-restoration/v1",
    "stepId": "unlock-account",
    "capability": "identity.account.unlock",
    "effectiveRisk": "HIGH",
    "requiredApproval": "RESOLVER"
  }
}
```

Actor, authority, case identity, successor, assignment and time remain server-owned.
Changed review, exhausted budget or unrelated existing retry conflicts. A handback
needs its new epoch. Runtime planning is re-evaluated at the exact case version,
which is also locked at insertion. Never substitute later evidence or another operation.

First application returns 201; exact replay returns 200 with identical stored
timestamps/coordinates. Receipt fields are `commandId`, `runId`, `successorRunId`,
`supervisionRevision`, `expectedRunVersion`, `caseStreamVersion`, `retryPolicyRevision`,
`sourceAttemptNumber`, `maximumAttempts`, `startedAt` and `recordedAt`. These
acknowledge history, not current supervision, successor detail access or execution.

First application requires current predecessor supervision/resolver evidence.
Historical replay requires the original actor and current resolver evidence, not
continued supervision. It does not restore access after successor handover.
Foreign receipts are neutral absence; changed own intent conflicts. If a failed
POST never committed, exact retry may first apply original intent under current
gates. Never automatically retry or replace uncertain intent with a new command ID.

## Atomic effects and exclusions

One short transaction records RETRY_STARTED, predecessor SUPERSEDED, one fresh
successor, its initial supervision at epoch zero for the confirming actor, and
immutable audit. Browser assignments link their real retry command and authority;
machine subjects are not fabricated. The successor appears through its own
assigned-work gate, with independently evaluated approval/authorization requirements.

No approval, grant, consumption, receipt or provider key is inherited. No connector
is invoked. Case status and follow-up ownership do not change. Existing internal
successors cannot be adopted; exhausted-run escalation is a separate capability.

## Failure contract

All responses are no-store. Stable problems are `run-retry-conflict` (409),
`run-retry-disabled` and `run-retry-unavailable` (503), `invalid-run-retry` (400)
and neutral `assigned-resolution-run-not-found` (404). Existing session, identity
and CSRF problems retain their meaning. Problems contain no raw SQL, planner
cause or private authority identity. Browser-session-disabled deployments return
the existing browser-authentication-unavailable 503.

## Rollout and recovery

Deploy V20261016200000 and every updated assignment reader before enabling
`ERGON_RUN_SUPERVISOR_RETRY_ENABLED=true`. Unique-key/FK scans and locks need a
table/index-size, WAL, replication and disk budget; no backfill occurs. Existing
machine writers remain compatible. After a browser assignment exists, old
non-null machine-subject mappers are unsafe: disable commands and recover forward
with updated readers, preserving all audit. Disabling does not undo a retry.

Review/merge this backend before the dependent UI confirmation, privacy withdrawal
and executable guide. No UI or guide is implemented here. Synthetic sessions and
upstream fixtures do not verify real OIDC, production ingress or provider behavior.
Review and confirmation use the existing complete case-history evaluator; a
narrow response does not bound replay cost. Planning is evaluated again by the
runtime owner before insertion so changed meaning cannot bypass confirmation.
