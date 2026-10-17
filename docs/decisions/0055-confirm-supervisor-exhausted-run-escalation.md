# ADR 0055: Confirm supervisor-scoped exhausted-run escalation

- Status: Accepted
- Date: 2026-10-17
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The maintainer approved issue #167's authorization and narrow disclosure policy.
Browser retry ends at the runtime's total-attempt ceiling. Existing internal
escalation atomically records terminal failure and opens human work, but its
bearer ingress, tenant-wide resolver permission and private attribution payload
are not a browser contract. Run-based internal replay is not actor-bound intent.

## Decision

Add a confidential, no-store supervisor/resolver review and a separately
default-off session/CSRF command. First application requires exact current
supervision, current tenant-wide RESOLVER evidence, ACTION_FAILED at sequence
one and the exact reviewed attempt, retry-policy revision, ceiling and exhaustion
reason. Re-evaluate after lock waits. Only runtime attempt-limit denial permits
escalation; remaining budget is not permission to skip recovery.

Reuse the runtime escalation owner in one transaction with immutable browser
audit. Event, ESCALATED projection and one unassigned access-restoration work
item commit together. Neither case state nor ownership changes; no capability,
notification, compensation or further attempt occurs. There is no new action
plan or raw-evidence disclosure because this command ends recovery, not execution.

Lock run NO KEY UPDATE, namespaced tenant command, then run state. This matches
retry/handover and remains compatible with internal writers' key-share FK
checks. Recheck current authority after waits and before audit. Runtime policy
must equal the reviewed exhaustion; never adopt another command's prior escalation.

Exact replay requires the original actor and current resolver evidence, not
continued supervision. Return only immutable creation acknowledgement: exact
command/run/policy coordinates, escalation/work identities, server-selected
queue, original OPEN creation status and timestamps. This is not live status,
ownership or access. Subsequent private reads retain their own current gates.
Different actor/run is neutral absence; changed own intent conflicts. Explicit
uncertain replay may first apply the original intent if no command committed.

## Alternatives considered

### Expose the internal escalation endpoint

- Benefits: no separate orchestration or audit.
- Costs and risks: broader authorization, private attribution and cross-actor replay.
- Reason not selected: browser confirmation must be exact and supervisor-scoped.

### Automatically escalate on retry exhaustion or claim the work for its supervisor

- Benefits: fewer operator interactions.
- Costs and risks: obscures accountable intent and conflates supervision with work ownership.
- Reason not selected: escalation and subsequent claiming are separate decisions.

## Consequences

### Positive

Exhausted automated work becomes actionable without a third attempt, implicit
case closure, duplicate follow-up or browser-selected recipient.

### Negative

New audit and supporting semantic keys add storage and migration lock/scan cost.
Navigation can discard uncertain local intent without undoing committed work.

### Neutral or follow-up

Backend review/merge precedes the dependent Console confirmation and executable
guide. General assignment, follow-up completion, leases and compensation remain
separate capabilities. Terminal runs cannot currently hand over supervision;
historical replay still deliberately does not depend on a current supervision read.

## Compatibility and migration

V20261017120000 adds audit and exact-source keys without backfill or changes to
existing runtime history, assignment readers or internal HTTP semantics. Source
event/work UNIQUE validation scans existing rows under write-conflicting locks.
Budget table/index sizes, lock duration, WAL/replication lag and free disk.

Deploy schema and new backend before enabling ERGON_RUN_SUPERVISOR_ESCALATION_ENABLED,
which defaults false. Disable new commands for rollback; preserve committed
event, work and audit. Old internal writers remain compatible with the expanded
schema. Recover forward if acknowledgements are unavailable; never delete audit
or undo terminal events. Browser sessions retain their separate rollout switch.

## Security and operations

The verified confidential session supplies identity; CSRF precedes dispatch.
All lookups, locks, unique and foreign keys are tenant-scoped. Audit binds the
actual historical supervisor, resolver attestation, event and created work, not
merely their independent IDs. Existing trusted database-role access remains;
this is not RLS, production ingress or permission for direct database users.
Problems omit SQL, internal exceptions, private actors and evidence. Failed
acknowledgement is uncertain, not proof of rollback; no automatic POST retry.

## Validation

PostgreSQL/MockMvc tests cover first application, exact/foreign/stale replay,
remaining budget, terminal/disabled/expired context, handover, policy change,
concurrent internal/browser decisions, atomic rollback and immutable semantic
source constraints. Exercise empty schema and released-schema upgrade with
existing failed attempts, supervision and escalated work. Full Maven verification
includes policy, Ktlint and Detekt. Synthetic sessions/source fixtures do not
certify a real IdP, provider or production browser deployment.
