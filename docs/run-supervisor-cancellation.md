# Confirmed untouched-run cancellation

## TL;DR

The current supervisor with current tenant-wide RESOLVER authority may cancel
only a version-zero run before any durable authorization or execution record.
This terminates the run, not a provider operation or the case. Commands default
off. Upgrade writer code before the migration (or quiesce writes); deploy
compatible UI readers before enabling commands.

## Review and confirmation

With browser sessions enabled, GET
`/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/cancellation-review`.
Current supervision/resolver gates apply even while cancellation is disabled.
The no-store response supplies `supported`, `observedAt`, `supervisionRevision`,
`runVersion`, `canCancel` and one stable `reason`:

| Reason | Meaning |
|:--|:--|
| DISABLED | Rollout flag is off. |
| RUN_NOT_UNTOUCHED | State/version is no longer eligible. |
| AUTHORIZATION_OR_EXECUTION_RECORDED | A grant or later execution/history record exists. |
| ELIGIBLE | Advisory gates currently permit confirmation; revalidation is mandatory. |

POST the same base path plus `/cancellation-commands` with session CSRF:

```json
{"commandId":"bce5ddcb-8b43-4c17-a32c-f9896649e730","expectedSupervisionRevision":0,"expectedRunVersion":0}
```

The server supplies actor, event identity/time and OPERATOR_REQUESTED reason.
First application returns 201; exact replay 200. The no-store acknowledgement
contains `commandId`, `runId`, `supervisionRevision`, `expectedRunVersion`,
`reason`, `cancelledAt` and `recordedAt`, without private attribution. No caller
reason, tool arguments, recipient or queue can select cancellation behavior.

## Meaning and recovery

Only WAITING_FOR_APPROVAL/READY_FOR_AUTHORIZATION at version zero qualifies.
Any durable grant, including an unconsumed grant, consumption, execution
admission/attempt, receipt or previous transition denies first application.
Approval requests/decisions remain immutable history but cannot create new
authorization after cancellation. Run/event/audit commit atomically; no remote
call is made. CANCELLED leaves active discovery, while exact Console reads retain
current-supervisor/resolver access and existing evidence scope. Cancellation
does not accept proof, close a case, open human work, refund a retry or restart.

Upgraded legacy approval/authorization writes also serialize on the run fence
before child-row locks. New writes on a cancelled run return 409 with problem type
`urn:ergon:problem:resolution-run-cancelled`; existing historical reads and exact
acknowledgements do not insert and retain their previous meaning.

Do not automatically retry a POST. Failed acknowledgement may mean committed
or absent intent. Explicitly replay the unchanged command to recover; if absent,
it may first apply under fresh current gates. Exact historical replay needs the
original actor and current resolver authority, not continued supervision, and
never restores access. Changed own intent returns 409; foreign/unauthorized run
context remains neutral absence. Session authentication can reject an unregistered
tenant identity before that run-level gate. Malformed intent is 400; disabled or
unavailable acknowledgement is 503. Problems never include raw SQL/provider data.

## Rollout and operations

First upgrade ALL approval/authorization writers with cancellation false and
`spring.flyway.target=20261017120000`, deferring this migration during that
code-first rollout. Then remove the target and apply V20261018120000. Alternatively
quiesce all approval/authorization/execution writes throughout schema-and-code
deployment. Do not allow the first new instance's automatic Flyway startup to
install run-locking triggers while old child-first writers are still active:
they can invert the lock order even while cancellation is disabled.

After upgrading all state/event readers and fences, deploy a UI that recognizes
CANCELLED, then enable the separate
`ERGON_RUN_SUPERVISOR_CANCELLATION_ENABLED=true`. Keep it false for this backend-only
delivery until the dependent UI/guide is reviewed. Browser-session enablement is
separate. Old servers omit the review route; clients treat that as unsupported.

The migration validates state/event CHECKs and builds an event supporting key
under write-conflicting locks. Budget table/index sizes, scan/lock windows, WAL,
replication lag and disk. Run-level serialization now covers legacy approval,
grant and consumption writers before child-row locks, with insert integrity
fences as defense in depth. These are not credentials or a direct-database
authorization model. Monitor lock contention and sanitized command failures;
do not log private request/evidence payloads.

Disable new commands for behavioral rollback, retain history and recover forward.
From trigger installation onward, old child-first writers cannot safely resume,
even before the first cancellation. After first cancellation an old binary unaware
of CANCELLED also cannot safely read history; deleting audit or downgrading enum
readers is not rollback. Authorized,
admitted or uncertain work remains ineligible: use its existing explicit
[execution recovery](run-capability-execution.md), not this cancellation route.
See [ADR 0056](decisions/0056-cancel-untouched-supervised-runs.md).
