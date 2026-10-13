# ADR 0047: Expose sequence-aligned assigned run transitions

- Status: Proposed
- Date: 2026-10-13
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The runtime already commits immutable resolution-run events with its current
state projection. Each attempt supports one capability result and one terminal
decision, not a multi-step executor. The assigned Console exposes a measured
receipt but cannot show whether that result was projected or what terminal
transition followed it. Receipt persistence precedes separate state projection.

## Decision

- Extend the existing assigned-only Console GET with `transitions`, an ordered
  array of sequence, event type, source/destination state, occurrence time and
  database recording time. Expose no event, actor, authority, grant, consumption,
  provider, proof, successor-run or work-item coordinates.
- Read only after the existing exact-assignee/current-resolver gate. Capture
  the state revision, then load the tenant/run prefix through that sequence.
  Under READ COMMITTED a concurrent append may be visible to the second SELECT,
  but cannot enter this earlier prefix. Immutable events need no reader lock
  or repeatable-read transaction. Use the existing composite primary key.
- Put this sanitized projection query behind the consuming application's
  `RecordedRunTransitionReader`, with its PostgreSQL adapter. It does not grow
  the event-writing port or mix rich append payloads into the Console query.
- Validate complete sequences zero through two, event shapes, initial/chained
  states, final state and exact final occurrence/state-update equality. A
  nonempty prefix must agree with its immutable capability receipt's outcome
  and completion instant. Corrupt persisted metadata fails closed as an
  internal invariant failure, never a successful partial history.
- Sequence establishes order; wall-clock corrections can move timestamps
  backwards. These observations are not latency inputs. An empty revision-zero
  prefix remains valid even when a receipt is already durable.

## Alternatives considered

### Load the whole stream without a revision bound

- Benefits: one simple list query.
- Costs and risks: a later append can contradict the state already read.
- Reason not selected: immutable sequence prefixes supply consistent meaning.

### Lock state or require repeatable-read isolation

- Benefits: a broader database snapshot.
- Costs and risks: stronger coupling and contention for a small read model.
- Reason not selected: current authority checks and immutable bounded history
  require neither a new transaction policy nor writer interference.

### Add a multi-step executor or expose full event payloads

- Benefits: a richer waterfall and proof inspection.
- Costs and risks: changes authority and persisted meaning; leaks unrelated
  private coordinates and implies unimplemented execution semantics.
- Reason not selected: this slice consumes existing facts, not invented stages.

## Consequences

### Positive

Assigned supervisors can distinguish receipt recording, result projection and
terminal decision without confusing them with inspected outcome evidence.

### Negative

The history contains at most two transitions per attempt. It is not live,
contains no nested spans or cost, and does not expose accepted proof payloads.

### Neutral or follow-up

No schema, writer, endpoint, cache, external dependency or execution-control change.
Multi-step runs require a separately reviewed stream/reader contract.

## Compatibility and migration

This additive response uses existing durable rows; no migration or backfill.
Old readers ignore the new array. New browsers accept an omitted member from
old servers as unsupported, not an empty stream; explicit null is invalid.
Roll out either repository independently. Rollback removes this presentation
and read member together, leaving immutable runtime records untouched.

## Security and operations

Keep browser OIDC, tenant scope, current resolver evidence, neutral protected
404s and no-store. This is snapshot access, not an exclusive lease or continuous
revocation guarantee. No remote call enters a database transaction. Bounded
reads and sanitized fields add no payload logs or telemetry identifiers.

## Validation

Application tests reject missing, reordered, foreign and stale prefixes and
accept backwards clocks and failed-run terminal paths. PostgreSQL tests prove
tenant/run filtering, prefix exclusion of a persisted later append and the
real gated service projection. Existing authority-loss tests still withhold
resources. Browser contract tests prove metadata shape and private-field
omission. UI codec, disclosure, accessibility and executable-guide tests cover
the consumer; synthetic guide receipts do not establish live OIDC integration.
