# ADR 0048: Allow revisioned browser supervisor handover

- Status: Proposed
- Date: 2026-10-14
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

[ADR 0045](0045-assign-resolution-run-supervisors.md) records one immutable
machine-created initial assignment. That fact currently grants browser read
access while the original assignee has current resolver authority. Rewriting
it would destroy attribution and make old machine-command replay ambiguous.
The approved next slice allows the current supervisor to confirm a transfer
to another eligible tenant resolver; it does not authorize self-assignment,
forced takeover, a lease or execution control.

## Decision

- Preserve the initial assignment and its machine-only creation/replay contract.
  Add immutable supervision events and a transactionally maintained current
  projection. Revision zero mirrors the initial assignment; each handover
  advances supervision by one. Runtime state versions remain independent.
- Gate all upgraded discovery/detail reads against current supervision and
  current tenant-wide `RESOLVER` evidence. Preserve initial assignment ordering
  and timestamp for existing keyset cursors. Returning to a former actor creates
  a new epoch, never resurrects their old current assignment.
- Add same-origin session contracts for current-epoch recipient discovery and
  CSRF-protected handover. The browser names a recipient and exact command ID
  and expected revision; authentication supplies the outgoing actor. Expose only
  bounded eligible registered resolver IDs, excluding the caller, with a private
  UUID cursor. There is no name/email/provider-subject directory.
- For first application, require the exact current supervisor, unexpired
  outgoing and incoming tenant-wide resolver evidence, another recipient and
  an active run. Serialize on the existing tenant/run lock, then the command
  advisory lock, then the runtime-state lock. Append audit and advance the
  projection in one short transaction; no remote call enters it.
- Attribute both authority attestations and actors in server-side audit only.
  A receipt contains command/run identity, new supervision revision and stored
  occurrence/recording instants. Return the PostgreSQL-stored occurrence instant
  on the first response so precision loss cannot change a later replay receipt.
- An exact historical command may replay for its originating actor with current
  resolver evidence even after transfer removed their current supervision.
  Replaying never applies a transfer again, including after a handback. Reject
  foreign receipts neutrally and changed own intent as a conflict.
- Use an additive response pair `supervisionRevision` and `handoverSupported`.
  Older servers omit both; consumers must not infer support or revision zero.
  New servers return both, with support false while disabled or terminal.
- Enable commands only with `ERGON_RUN_SUPERVISOR_HANDOVER_ENABLED=true`, default
  false. Current-projection access gates apply even when commands are disabled.

## Alternatives considered

### Mutate the original assignment

- Benefits: fewer tables.
- Costs and risks: erases machine attribution and changes immutable replay and
  cursor meaning.
- Reason not selected: append handover facts while preserving the original.

### Allow any resolver to nominate themselves

- Benefits: simpler takeover.
- Costs and risks: knowledge of a run ID expands access to an unassigned actor.
- Reason not selected: only the current supervisor may transfer existing access.

### Introduce a lease without enforceable steering commands

- Benefits: matches a visual countdown.
- Costs and risks: unused coordination state and a misleading control promise.
- Reason not selected: handover is useful now; lease/fenced execution needs its
  own reviewed runtime contract.

## Consequences

### Positive

Supervisors can delegate a live access relationship without erasing history,
double-transferring an uncertain command, or granting execution authority.

### Negative

Directory entries are IDs only. There is no recipient acknowledgement,
notification or forced recovery for departed supervisors. Historic replay
requires current outgoing resolver evidence. Mixed-version backend rollback
becomes unsafe after any transfer.

### Neutral or follow-up

Assignment remains snapshot access, not continuous revocation or an exclusive
lease. Approval, steering, follow-up ownership and runtime/case state are unchanged.

## Compatibility and migration

The migration adds events/projection, an exact initial-assignment foreign key,
and a trigger that initializes new initial assignments from old and new writers.
Backfill existing assignments once, preserving timestamps and attribution.
Assess the existing-table constraint scan, write-conflicting locks, WAL and disk
headroom before applying to a populated environment.

Deploy the schema and projection-aware backend readers everywhere before
enabling handover. Old UI readers can ignore the additive metadata because
authorization remains at the upgraded BFF. Old backend readers are unsafe
after a transfer: they still use the original assignee. Before the first
transfer, disable commands to roll back; afterwards disable new commands and
recover forward with current-projection readers. Never delete audit history
or revert to an initial-assignment gate to recover availability.

## Security and operations

Every key, query, lock, event and foreign key retains tenant scope. Anonymous
requests, unregistered identities, missing/foreign supervision and expired
authority fail closed. A selected recipient is rechecked at the command instant;
directory membership is not authority. Missing detail remains a neutral 404.
Transfers and candidate responses are no-store. No token, provider subject,
authority evidence or incumbent identity is exposed through problems/receipts.

The browser withdraws private context before POST, keeps immutable local replay
intent only for uncertain results and never automatically retries transfers.
Navigation can lose that intent; it cannot cancel a committed server effect.
Production IdP provisioning, rate limits, durable sessions and live browser-to-
provider integration remain separate readiness work.

## Validation

Application tests prove disabled mode, authority-first receipt access, exact
replay, changed/foreign intent, stale epochs, active-state and eligibility gates.
Real PostgreSQL tests prove competing/exact concurrent commands, audit immutability,
atomic projection rollback, outgoing access loss and incoming access through
MockMvc with synthetic OIDC authentication. Migration tests cover empty schema,
released-schema backfill and subsequent old-style writers. UI protocol/cache,
component, Chromium and the executable synthetic guide verify the consumer.
Those synthetic sessions do not prove real IdP login or production rollout.
