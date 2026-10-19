# ADR 0057: Fence browser run control

- Status: Accepted
- Date: 2026-10-18
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers; policy approved in issue #171
- Supersedes:
- Superseded by:

## Context

Supervision establishes human authority, not exclusive browser control. Two
sessions can submit competing commands. Issue #171's approved policy requires
session-scoped leases, durable fencing and exact uncertain-command recovery.

## Decision

One authenticated session may hold control for a tenant/run/supervision epoch.
Only the current supervisor with current tenant-wide RESOLVER evidence acquires
or explicitly renews. The server generates a random session binding and fixed
120-second expiry; callers cannot choose actor, holder, TTL or time. There is no
forced takeover or release of another session's unexpired lease.

Acquisition allocates a lease UUID and monotonically increasing per-run fence.
Renewal retains both; release ends admission without removing history. Lifecycle
sequence is independent of acquisition fence. Handover invalidates the old epoch;
terminal state prevents new admission. Retry successors inherit no control.

All seven browser command families require the original public lease/fence and
epoch for new admission, including each additional execution recovery attempt.
Retain existing authorization/prerequisite checks and run-first lock order. Check
session, fence, epoch, exclusive expiry and current resolver after potentially
blocking command writes, immediately before transaction completion. Writes remain
provisional: a failed final gate rolls back grants, case changes, successor
assignment and command audit together. Append its original lease admission in
the same transaction, referencing immutable rows under the already held run lock.
Provider calls remain outside transactions. Trusted runtime paths are unaffected.

After existing original-actor/current-resolver/exact-intent validation, historical
replay compares the original public fence, not live ownership or session binding.
It never reacquires, renews or dispatches. Legacy history replays without a fence.
An absent uncertain command can first apply only its original still-valid fence.
Receipt-only acknowledgement/projection repair does not dispatch. Control loss
never revokes committed provider work or undoes its history.

## Alternatives considered

### Gate only HTTP controllers

- Benefits: Smaller transport change.
- Costs and risks: Expiry during database waits escapes checks; audit is not atomic.
- Reason not selected: Does not establish the approved durability fence.

### Implicit renewal, forced takeover or provider transaction locks

- Benefits: Less explicit browser coordination.
- Costs and risks: Reads grant control, takeover breaks exclusivity, remote calls
  retain locks or appear cancellable.
- Reason not selected: Violates approved authority and recovery boundaries.

## Consequences

### Positive

Exclusive browser effects preserve human authority, attributable audit and lost
acknowledgement recovery without blocking runtime progression.

### Negative

Expiry may roll back substantial provisional database work. New reviewed intent
must not silently replace an uncertain command's fence. Every browser writer must
participate; this is not database authorization against arbitrary SQL.

### Neutral or follow-up

Process-local sessions remain the deployment constraint. Lost sessions wait for
expiry rather than transferring control. UI and executable guide follow backend
review/merge in their dependent PR.
The BFF remains single-instance; this change does not add a shared session store
or supported horizontal deployment.

## Compatibility and migration

Additive immutable lifecycle/admission histories are authoritative; heads project
the latest lifecycle event. No existing business rows change or receive fabricated
leases. Default `ergon.run-supervision.control.enabled=false` retains legacy
unfenced commands. Supplied fences while disabled fail rather than imply protection;
previously fenced history remains exactly replayable after disabling.

Deploy schema, every upgraded backend writer/reader, then compatible UI. Quiesce
browser commands while enabling all writers together. Old writers are unsafe
while enabled; old UI missing fences fails closed. Disabling explicitly relaxes
protection, not effects. Retain history and recover forward; never downgrade an
enabled writer. ADR 0056's earlier lock-order rollout remains independently
required. Follow the [operating procedure](../operations/browser-run-control.md).

## Security and operations

Use existing session authentication, tenant actor resolution and CSRF. Status is
current-supervisor-only, private/no-store, with neutral absence on denial. Another
session's holder/fence is never disclosed. Lifecycle receipts exclude holders,
authority attestations, evidence and provider subjects. Public lease coordinates
are not bearer credentials. Histories retain random internal session correlation
under restricted database/audit access; never log cookies or publish that history.

## Validation

Real PostgreSQL tests cover acquisition races, exact replay, post-lock expiry,
atomic rollback, epoch/terminal invalidation, tenant isolation and immutable audit.
Verify HTTP authentication, CSRF, disclosure and all writer composition. Exercise
empty-chain and populated released-schema upgrades, focused checks and full Maven
verification before delivery.
