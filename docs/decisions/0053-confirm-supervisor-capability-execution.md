# ADR 0053: Confirm supervisor capability execution and explicit recovery

- Status: Accepted
- Date: 2026-10-16
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

Issue #163's authorization and recovery policy was explicitly approved by the
maintainer. Supervision alone still grants no capability authority. Existing
approval, grant, consumption, invocation and result projection have distinct
owners; a browser must not bypass them or hold their locks across connector I/O.

## Decision

Add separately default-off session/CSRF execution commands for the existing
single approved step. First admission requires the current supervisor and
tenant-wide RESOLVER evidence, exact supervision/run revisions and the exact
latest approved request/decision. Derive scope from the run, never browser tool
arguments. Reuse grant derivation and consumption in the admission transaction.
Previously derived internal grants conflict rather than being silently adopted.

Commit immutable command and attempt audit with consumption before invocation.
Lock order is run, namespaced command/attempt keys, state, decision and
grant. Recheck resolver evidence and supervision after waits and immediately
before admission. The existing services recheck the approval/consumption window.
Foreign-key bindings preserve reservation, supervision and resolver attribution.

Read immutable approval requests without a request UPDATE lock: the run lock
already serializes replacement, and legacy grant INSERT foreign-key checks must
not form a request/decision lock cycle with browser admission.

The first POST dispatches only when it inserts the first admission. Repeating
that POST is status/replay only, including while incomplete. Explicit recovery
uses a separate attempt UUID and fresh supervision/run revisions; repeated
attempt UUIDs never dispatch twice. Each newly admitted attempt uses the original
consumption/provider key. Concurrent distinct explicit attempts may reach the
connector, whose existing idempotency contract guarantees one provider operation.
No automatic retry, background worker, lease or lock across I/O is introduced.

Only the original actor who is again/still current supervisor with current
resolver evidence may admit recovery. A handback needs the new epoch in the
recovery request; old epochs cannot resume. A different incoming supervisor
cannot adopt another actor's browser command. A former supervisor with current
resolver evidence may replay their own original command/attempt acknowledgement
without dispatch. Other actors see neutral absence. New recovery of a completed
command conflicts; an already admitted effect is not cancelled by later handover
or expiry. Trusted operator recovery remains necessary if the originator cannot
return; no unsafe internal route is promoted as a production recovery API.

Terminal receipts and their sequence-one transition are read from their existing
owners, not duplicated. INCOMPLETE means no terminal receipt, not no external
effect. RESULT_RECORDED means projection remains incomplete. COMPLETED describes
the historical sequence-one result, not current run state or proof. Invocation
exceptions yield an incomplete acknowledgement without fabricated outcomes;
explicit recovery reuses the reservation even after its approval window expires.
Projection failures preserve the receipt and can be repaired by explicit recovery
without another connector call. Unexpected database/invariant failures propagate
as safe unavailable problems and leave their durable boundary intact.

## Alternatives considered

### Invoke internal endpoints directly from the browser

- Benefits: less orchestration.
- Costs and risks: no supervisor fencing, attributed intent or safe replay.
- Reason not selected: internal development ingress is not browser authority.

### Hold a lock through invocation or automatically retry uncertain commands

- Benefits: apparent single-caller serialization.
- Costs and risks: remote effects are not transactional; stalls and hidden retries.
- Reason not selected: durable reservation and provider idempotency own recovery.

## Consequences

### Positive

One reviewed capability can execute with accountable admission and explicit,
idempotent recovery while connector completion remains separate from proof.

### Negative

There is no autonomous recovery after a process crash. A different supervisor
cannot take over the original actor's command. This restriction is deliberate,
not permission to use a development endpoint publicly. Real provider calls still
need adapter-owned timeouts and provider-enforced idempotency.

### Neutral or follow-up

The dependent UI and executable guide follow only after backend PR review/merge.
General steering, cancellation, compensation and production recovery are deferred.

## Compatibility and migration

V20261016120000 adds immutable command/attempt tables and supporting grant/consumption
uniqueness; the existing table scan/index build takes a write-conflicting lock.
Assess size, WAL, disk and lock duration before deployment. No backfill or
existing-row mutation. Deploy schema/code before enabling
ERGON_RUN_SUPERVISOR_EXECUTION_ENABLED. Disable the flag for application rollback;
retain all reservations and audit. Older binaries ignore the additive tables.

## Security and operations

Session identity and CSRF precede application dispatch. All lookups and locks are
tenant-scoped and no-store. Receipts omit actor, attestation, grant, consumption,
provider reference and connector payload. Current-supervisor review never reveals
another actor's command identity. Disabled commands cannot resume execution.
Do not log connector exceptions or return their messages as problems. Incomplete
results are operationally uncertain, never a success or an automatic retry hint.

## Validation

Real PostgreSQL tests cover admission, replay, scope and expiry, concurrency,
crash/exception boundaries, recovery after expiry, handover/handback fencing,
atomic rollback, append-only attribution, migration upgrade and browser security.
Application tests prove no transaction encloses I/O. Run focused policy and full
Maven verification. Synthetic sessions/local connectors do not prove real IdP,
remote-provider guarantees or production recovery readiness.
