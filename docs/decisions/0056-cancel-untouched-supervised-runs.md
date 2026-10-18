# ADR 0056: Cancel untouched supervised runs

- Status: Accepted
- Date: 2026-10-18
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The maintainer approved issue #169's cancellation, authorization and recovery
policy. Supervisors need to terminate untouched work without suggesting that a
provider action was stopped. Legacy grant/consumption routes bypass browser
admission, so a browser-only fence cannot establish absence of executable work.

## Decision

Permit cancellation only at version zero in WAITING_FOR_APPROVAL or
READY_FOR_AUTHORIZATION, with no durable grant, consumption, execution
command/attempt, receipt or run event. Pending requests/decisions remain history.
First application requires the exact current supervision revision and current
tenant-wide RESOLVER evidence, checked after lock waits. Session identity supplies
the actor. The server owns event identity, timestamp and OPERATOR_REQUESTED reason.

Acquire the run NO KEY UPDATE fence, tenant command key and state lock in that
order. Legacy approval/authorization lookup adapters acquire the same run fence
before child locks; insert triggers additionally reject new authorization for
CANCELLED runs. Do not invert run/decision/grant locks. Existing runtime
state-first writers retain their lock order and FK key-share compatibility.
Since even an unconsumed grant precludes cancellation, invocation of an existing
consumption cannot race a successful cancellation into provider I/O.

Commit RUN_CANCELLED sequence one, terminal CANCELLED projection and attributed
immutable exact-command audit together. No connector I/O, case closure, proof,
human work, compensation, budget refund or restart occurs. No supported path
creates new approval/authorization/execution history on a cancelled run.

Exact replay requires the original actor and current resolver evidence, not
continued supervision. Changed own intent conflicts; foreign context is neutral
absence. Explicit unchanged replay may first apply an absent original command
under current first-application gates. There is no automatic POST retry. Review
and acknowledgement are private no-store observations, never access tokens.
Cancelled runs leave active discovery; exact reads retain their existing gates
and existing evidence scope, without new private attribution disclosure.

## Alternatives considered

### Guard only browser admission

- Benefits: smaller change.
- Costs and risks: legacy authorization could become dispatchable after cancellation.
- Reason not selected: cancellation must fence every supported reservation path.

### Cancel already authorized or in-flight work

- Benefits: broader stop control.
- Costs and risks: revocation and uncertain provider completion need a separate protocol.
- Reason not selected: this slice promises termination before authorization only.

## Consequences

### Positive

One attributable terminal decision cannot coexist with a supported executable reservation.

### Negative

Legacy approval/authorization writes now serialize per run; schema validation and
supporting keys add lock, scan, WAL and disk costs.

### Neutral or follow-up

Backend review/merge precedes the dependent UI confirmation/recovery and guide.
Leases, pause/resume, general revocation and provider cancellation remain absent.

## Compatibility and migration

V20261018120000 expands state/event shapes and adds immutable audit plus writer
triggers without backfill. Budget state/event scan and index sizes and migration
lock windows. The triggers acquire a run lock even with cancellation disabled:
old child-first writers could deadlock with browser commands after DDL alone.
Do not let the first upgraded instance automatically install this migration in
a still-old writer fleet. Roll out this binary with cancellation false and
`spring.flyway.target=20261017120000` until ALL approval/authorization writers
use the shared fence. Then remove the target and apply the migration. Alternatively
quiesce all approval/authorization/execution writes for the whole schema-and-code
deployment. The new binary's legacy writes work on the previous schema; cancellation
commands remain disabled and do not consult their absent audit table.

Deploy UI CANCELLED readers before enabling ERGON_RUN_SUPERVISOR_CANCELLATION_ENABLED
(default false). Writer downgrade is unsafe from trigger installation onward,
even before first cancellation; old enum readers are additionally unsafe after
first use. Disabling commands retains audit and fences. Recover forward, never
delete terminal history or resume old child-first writers.

## Security and operations

Confidential session authentication and CSRF precede dispatch. All keys, locks
and queries retain tenant scope. Audit binds exact historical supervision,
resolver evidence and event semantics, not independent nearby UUIDs. Existing
trusted database-role access remains; triggers are integrity fences, not RLS or
production ingress security. Failures disclose no SQL, provider payload or private
authority. An unavailable acknowledgement is uncertain, not proof of rollback.

## Validation

Exercise domain shape, current authority, stale epochs, exact/changed replay,
handover, disabled/session/CSRF/no-store/neutral absence, terminal discovery/detail,
atomic rollback and PostgreSQL races against legacy grants and browser admission.
Verify empty and released-schema upgrade, existing history preservation and full
Maven policy/static-analysis/tests. Synthetic identity/provider fixtures do not
certify a real IdP, external cancellation or production browser deployment.
