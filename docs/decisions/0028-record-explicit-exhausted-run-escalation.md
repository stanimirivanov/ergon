# 0028. Record explicit exhausted-run escalation

- Status: proposed
- Date: 2026-09-15
- Owners: Ergon maintainers

## Context

A failed final attempt previously remained `ACTION_FAILED` with no durable
statement that automated recovery had ended. Treating exhaustion itself as an
escalation would hide who accepted human follow-up and under which policy.

## Decision

Add an authenticated, resolver-authorized command that appends
`ESCALATION_REQUESTED` only when the current versioned retry policy denies the
failed attempt because its ceiling was reached. Freeze the resolver actor and
authority evidence together with the denial reason, revision, source attempt,
and ceiling. Advance only the run to terminal `ESCALATED`; keep the case open.

Replay requires current resolver authority but returns the original event before
re-evaluating policy. Database constraints bind the denial to the run's actual
attempt and its sequence-one failure.

## Alternatives

Automatic escalation on exhaustion was rejected because it lacks an accountable
human decision. Reusing `ACTION_FAILED` was rejected because requested follow-up
would remain implicit. Creating assignment and notification records in the same
command was deferred so each external or organizational effect has its own
idempotent contract.

## Consequences

Exhausted recovery has an explicit auditable terminal state without claiming the
case is resolved. This slice does not select an owner, notify a channel, retry,
compensate, or invoke a connector. The migration validates existing event and
state rows under table locks but does not rewrite history.

## Verification

Domain tests cover event construction, authority, attempt binding, and durable
rehydration. PostgreSQL-backed API tests cover authorization, remaining-budget
denial, first recording, replay, immutable audit fields, and the open case.
