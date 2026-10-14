# ADR 0049: Confirm supervisor-scoped browser approval decisions

- Status: Proposed
- Date: 2026-10-14
- Milestone: M03 - Human authority and policy
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The Resolver Console knows pinned approval requirements, not current request
or decision facts. The existing bearer decision boundary verifies exact authority
but has no browser supervision epoch or exact-command replay contract. The user
approved current-supervisor initiation with the request's matching authority.
Handover already defines historical supervision epochs and a current projection.

## Decision

- Extend the assigned Console with sanitized `approvalReview`: server observation,
  lifecycle, latest request in durable recording order, historical decision and
  observed `canDecide`. No actor, attestation or provider identity is disclosed.
  Missing on an older server means unsupported; DECIDED outranks later expiry.
- Add session/CSRF POST to the exact tenant/run/request `decisions` route. A first
  command requires current supervisor and resolver evidence, expected supervision
  revision, WAITING_FOR_APPROVAL, latest unexpired request and exact approval
  authority. REQUESTER is case-scoped; RESOLVER is tenant-wide, not a substitute.
  Both approval and rejection use these gates. Authentication supplies actor;
  the browser supplies only command identity, expected revision and outcome.
- Lock run, tenant/command, runtime state, then request inside one short local
  transaction. This serializes request replacement and handover; request locks
  and existing uniqueness also serialize legacy decisions. Run serialization
  uses FOR NO KEY UPDATE, compatible with legacy INSERT foreign-key KEY SHARE
  checks while those writers hold a request lock; FOR UPDATE would create a
  lock cycle. Persist the existing immutable decision and browser receipt atomically, binding
  the actor to the exact historical supervision event through tenant-safe FKs.
- Return 201 for first application and 200 for exact historical replay. Only the
  originating actor with current resolver evidence may recover a receipt, even
  after losing supervision. Replay checks all original intent, never records
  another decision, and does not require still-current requester evidence.
  Changed own intent conflicts; foreign receipts remain neutral absence.
  Floor the decision-check clock to persisted microsecond precision before
  validation so rounding cannot move a decision onto its exclusive expiry.
  Capture that final instant after state/request lock waits and recheck resolver
  evidence there as well as exact approval evidence; an earlier access check
  cannot preserve authority or request validity across a wait.
  Read persisted timestamps for first acknowledgement as well as replay.
- Recording APPROVED or REJECTED creates no grant, invocation, run transition
  or outcome proof. Existing bearer decision semantics remain unchanged; this
  stricter policy belongs to the Console command, not a global API restriction.
- Default `ERGON_RUN_SUPERVISOR_APPROVAL_ENABLED` to false. Current review remains
  available while commands are disabled. Request creation stays trusted/internal.

## Alternatives considered

### Expose the existing bearer decision command to browser code

- Benefits: less persistence and orchestration.
- Costs and risks: lacks supervision fencing and safe exact replay, and changes
  authentication or legacy behavior.
- Reason not selected: preserve that boundary and add a narrow BFF capability.

### Treat resolver supervision as requester authority or approval as execution

- Benefits: a simpler apparent workflow.
- Costs and risks: bypasses case scope and conflates independent human,
  authorization, execution and verification boundaries.
- Reason not selected: contradicts the explicitly approved authority policy.

## Consequences

### Positive

The Console can review and record real human prerequisites with deterministic
authority, concurrency and uncertain-result recovery, without inventing execution.

### Negative

A supervisor without required case-scoped requester evidence cannot decide that
request. The browser cannot provision that evidence or create a replacement.
Navigating away can discard local replay intent without undoing a committed write.

### Neutral or follow-up

Requester-facing decisions, grants, runtime continuation, withdrawal, leases,
steering and continuous revocation remain separate capabilities.

## Compatibility and migration

The additive receipt table does not backfill or alter legacy decisions. Its
supporting unique tuple scans and write-locks the existing decision table;
assess lock duration, size and headroom before a populated rollout. Deploy the
migration and upgraded backend before enabling commands. Older UIs ignore the
new projection; newer UIs display explicit unsupported state on older backends.
Disable new commands to roll back application code, retaining all audit facts.
Handover's separate prohibition on original-assignee backend rollback still applies.

## Security and operations

Resolve session identity and validate CSRF before application invocation. Scope
every key, query and lock by tenant. Missing/non-owner/foreign requests disclose
no incumbent or decision identity. All command responses and known problems are
no-store and omit causes, tokens and private attribution. Browser submission
withdraws private resources first; no mutation retries automatically. A lost
response permits explicit exact replay only, with fresh invocation-local CSRF.

## Validation

Real PostgreSQL/session tests cover both outcomes, exact requester scope, expiry,
non-owner and tenant isolation, CSRF, stale epochs, competing and exact concurrent
commands, historical replay, immutable receipts and no grant/state advancement.
Empty and released-schema upgrade tests preserve legacy decisions and writers.
UI protocol/cache/component and Chromium guide tests cover decoding, defects,
confirmation, private withdrawal, keyboard/reflow and safe recovery. Synthetic
sessions/guide fixtures do not prove live IdP login or a production deployment.
