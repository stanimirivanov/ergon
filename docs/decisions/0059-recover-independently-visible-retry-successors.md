# ADR 0059: Recover independently visible retry successors

- Status: Accepted
- Date: 2026-10-20
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers; standing authorization/disclosure approval
- Supersedes:
- Superseded by:

## Context

A lost browser retry acknowledgement or later Console visit loses local successor
navigation. Active discovery omits terminal runs. Replaying an originating command
is actor-specific and is not a general relationship lookup after handover. The
runtime already records one immutable RETRY_STARTED relationship per predecessor.

## Decision

Add a no-store GET retry-successor read. Require current exact predecessor
supervision and tenant-wide RESOLVER evidence before reading its retry event.
Independently apply the existing current-supervisor/resolver gate to the direct
successor. Return observation time, the predecessor supervision revision and a
nullable successor containing only run identity and its current supervision revision.

Absent retry, unassigned successor and inaccessible successor have identical null
responses. Disclose no hidden identity, reason, count, receipt or successor facts.
Terminal source and target runs remain eligible. Browser and internal retries use
the same runtime event; internal successors still need explicit trusted assignment.
Neither the originating actor nor possession of a browser command is required.

Reuse application-owned retry and assignment ports in one short transaction.
Checks share an authority observation instant but READ COMMITTED queries do not
promise an atomic cross-run supervision snapshot or continuous authority. Navigation
must reacquire fresh exact target access; neither returned epoch grants anything.
No lease or enabled command flag is needed. No recursive enumeration is performed.

## Alternatives considered

### Recover from command acknowledgements or active discovery

- Benefits: No additional read contract.
- Costs and risks: Requires retained original intent/actor or omits terminal work.
- Reason not selected: Does not recover durable navigation after a later visit.

### Reveal every related run under predecessor authority

- Benefits: Easier complete retry-tree rendering.
- Costs and risks: Leaks other supervisors' work and implies inherited access.
- Reason not selected: Each run retains its independent current authority boundary.

## Consequences

### Positive

Authorized supervisors can recover a direct successor independently of command
replay, browser state and active-run discovery, without new execution authority.

### Negative

Null cannot distinguish no retry from a hidden target. Access may change after a
successful read; target navigation can still fail and must withdraw private facts.

### Neutral or follow-up

The UI navigation and executable user guide follow backend review/merge. A full
retry graph, inherited supervision, new retry budgets and provider spans remain out
of scope. This relationship is a business retry, not same-reservation recovery.

## Compatibility and migration

This is an additive browser GET with no schema, index, dependency or configuration
change. Existing event uniqueness bounds the lookup to one relationship. Command,
receipt, predecessor-detail and internal contracts remain unchanged. Deploy backend
first; older backends lack this route. Rollback removes only the read and preserves
all retries and assignments. No command rollout flag is enabled.

## Security and operations

Existing confidential OIDC sessions and tenant actor resolution apply. Missing,
foreign, unassigned, former-supervisor and expired-authority predecessor access is
neutral 404 before retry lookup. Hidden targets are successful null. Known responses
are no-store; persistence or invariant errors produce cause-free run-retry-unavailable
503, never fabricated absence. Invalid UUIDs remain invalid-run-retry 400. Reads
create no commands, assignments, leases, grants, transitions, proof or provider I/O.
The endpoint returns no actor, attestation, event, command, session, fence, provider
reference or raw evidence. Trusted database access and production ingress are unchanged.

## Validation

Focused application/HTTP and PostgreSQL tests prove authorization-before-history,
independent target gates, terminal and disabled-command reads, internal/browser
origin, handover, tenancy, safe failures, exact fields and no read-induced writes.
Run repository policy and full reactor verification. Existing migration tests own
the unchanged schema; synthetic sessions/upstream fixtures do not certify live IdP
or provider readiness. The dependent guide will label simulated facts explicitly.
