# ADR 0058: Disclose supervisor-scoped execution admissions

- Status: Accepted
- Date: 2026-10-19
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers; disclosure policy approved in issue #173
- Supersedes:
- Superseded by:

## Context

The Console exposes terminal invocation measurements but not existing durable
browser execution admissions. Transient acknowledgements cannot explain prior
recovery admissions. Audit records no dispatch-start or per-call result spans.

## Decision

Add a read-only execution-history projection. Current exact supervision and
current tenant-wide RESOLVER evidence gate audit lookup at one observation
instant. Incoming supervisors can inspect sanitized history across epochs,
including terminal runs. Former supervisors receive neutral absence. Neither
a live control lease nor enabled execution-command flag is required.

Return observation time, current supervision revision, browser-command presence,
up to 100 admissions and explicit truncation. Each row contains INITIAL or
RECOVERY, historical supervision revision, admission time and recording time.
Identify INITIAL by the existing command-equal attempt ID, not timestamp order.
Exclude actor, command/attempt, session, fence, authority and provider identifiers.

Read command presence and at most 101 admissions in one SQL statement. Sort by
recorded_at then private attempt UUID ascending. This is deterministic display
order, not causal order. Clocks can move backward; the initial admission may not
be first or within truncated results. No total or stable cross-request prefix
is promised. Infer no dispatch state, per-admission result, duration or cost.

Application code owns authorization/transactions; a narrow read port owns the
projection; PostgreSQL implements bounded lookup using a tenant/command/time/UUID
index. HTTP maps explicit fields. A command without admissions fails as an
invariant violation, not empty history. Reads grant no recovery permission.

## Alternatives considered

### Publish original receipts or complete audit

- Benefits: Reuses existing payloads.
- Costs and risks: Discloses replay coordinates and previous actor attribution.
- Reason not selected: History inspection does not grant original-actor recovery.

### Infer a waterfall from timestamps

- Benefits: Resembles the concept without runtime instrumentation.
- Costs and risks: Recovery can repair projection without provider I/O; time
  differences are not measured execution duration.
- Reason not selected: Would present unsupported facts.

## Consequences

### Positive

The Console can explain admissions without transient state or new authority.

### Negative

Large histories truncate. Observation-time access is not continuous authority
or a guarantee against subsequent handover.

### Neutral or follow-up

UI and executable guide follow backend review/merge. Multi-step execution and
per-dispatch instrumentation remain separate capabilities.

## Compatibility and migration

The new GET leaves command/acknowledgement contracts unchanged. An additive
index scans attempts and blocks writes while building; budget index/WAL storage
and a maintenance window. No audit rewrite/backfill occurs. Old writers remain
compatible. Application rollback retains the index/audit. No flag is enabled.

## Security and operations

Existing confidential session and tenant-actor resolution apply. Missing,
foreign, unassigned and unauthorized runs share private absence before audit
lookup. Success and known failures are no-store. Persistence/invariant failures
return cause-free unavailability, never fabricated empty history. Reads create
no leases, grants, admissions, provider calls, transitions or proof. Missing
browser history does not imply absent internal execution or external effects.

## Validation

PostgreSQL-backed checks cover classification, handover, terminal access,
authorization-before-audit, tenancy, bounds, equal/backward timestamps and no
read-induced writes/I/O. Verify HTTP/session failures, empty migration chain,
populated upgrade, focused policy and full reactor verification.
