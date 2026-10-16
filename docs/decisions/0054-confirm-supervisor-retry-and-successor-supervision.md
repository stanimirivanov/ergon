# ADR 0054: Confirm supervisor retry and atomic successor supervision

- Status: Accepted
- Date: 2026-10-16
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The maintainer approved issue #165's retry authorization and atomic successor
assignment. Existing internal retry creates one bounded fresh run but does not
assign it. ADR 0053's invocation recovery retains the same reservation and cannot
restart a terminal failed provider operation.

## Decision

Add an authority-first no-store review and a separately default-off session/CSRF
command. First application requires exact current predecessor supervision,
current tenant-wide RESOLVER evidence, ACTION_FAILED at sequence one, the reviewed
supervision/run/case versions, full successor plan and retry policy/ceiling.
Re-evaluate planning; retain the contract revision, step and capability. The
existing policy permits two total attempts, not two additional retries.

Review discloses only planning coordinates, effective safeguards and eligibility.
It exposes no raw later evidence, authority identity or unrelated successor.
Review is advisory, not an authorization token.

Reuse the runtime retry owner. In the same transaction supersede the predecessor,
create its fresh successor, assign only that successor to the confirming actor,
and persist an immutable exact command receipt. This narrowly extends ADR 0045's
machine-only initial assignment boundary, not general browser self-assignment.
The browser chooses neither a recipient nor a successor ID. Copy no approval,
grant, consumption, receipt or provider key. Invoke no connector.

Machine assignment subjects remain unchanged. Browser-retry assignments have a
null machine subject and an exact non-null retry-command reference. Tenant-safe
mutually bound FKs require the assignment and command to commit together; the
existing trigger initializes current supervision at epoch zero. Never impersonate
a machine principal for a human action.

Lock order is predecessor run (NO KEY UPDATE), namespaced tenant command,
predecessor state, then case through the retry owner. Recheck authority and
supervision after waits, and runtime-selected authority after case locking.
Internal retry writers serialize on state/case; their predecessor key-share FK
checks remain compatible with the outer non-key lock. No remote I/O enters.

Exact replay requires the original actor and current resolver evidence, not
continued supervision. It acknowledges history without restoring successor access
or assigning again. Foreign receipts are neutral absence; changed own intent
conflicts. Never adopt an unrelated internal successor. Subsequent successor
reads always require its own current-supervisor gate.

## Alternatives considered

### Expose internal retry and assign in a second request

- Benefits: less browser-specific orchestration.
- Costs and risks: development ingress promotion and a crash leaving unsupervised work.
- Reason not selected: retry, attribution and supervision must commit atomically.

### Reuse the previous execution reservation

- Benefits: fewer new identities.
- Costs and risks: a terminal key replays the failed operation, not a fresh attempt.
- Reason not selected: invocation recovery and business retry have different meaning.

## Consequences

### Positive

Bounded failed work continues as a discoverable fresh successor without inherited
execution authority, arbitrary assignment or duplicate retries.

### Negative

All assignment readers must handle nullable provenance before enabling commands.
Older binaries cannot map browser assignments after first use. Browser navigation
may still discard local uncertain replay intent without undoing committed work.

### Neutral or follow-up

Backend review/merge precedes dependent UI and executable guide. Exhausted-run
escalation, general assignment, pause/cancel/branch, leases and compensation are deferred.

## Compatibility and migration

V20261016200000 expands assignment provenance and adds exact retry audit. No
existing row or runtime history is rewritten/backfilled. Unique-key and FK
validation scans runs/events/assignments under write-conflicting locks: budget
table/index size, lock duration, WAL, replication lag and disk. The assignment
FK is deferred solely for the atomic insert cycle.

Deploy schema and every updated assignment reader before enabling
ERGON_RUN_SUPERVISOR_RETRY_ENABLED (default false). Disable new commands for
behavioral rollback; after first use retain history and recover forward with
updated readers. Internal bearer retry semantics and old machine writes remain
compatible. Do not delete audit or undo supersession to recover availability.

## Security and operations

Session identity supplies actor/tenant registration; CSRF precedes dispatch.
Every query, key and lock retains tenant scope. Failed acknowledgements are
uncertain: exact POST retry can first commit original intent if never admitted,
or acknowledge an existing command without new effects. Never automatically
retry a mutation or substitute a fresh command ID. Receipts omit actor,
attestation, assigning identity, provider reference and raw evidence. Existing
trusted database application-role access remains unchanged; no RLS/production
ingress or autonomous dispatcher is introduced.

## Validation

Repository policy, focused PostgreSQL/MockMvc tests and full Maven verification
cover fresh requirements, disabled/expired/foreign denial, stale plan/policy/case,
internal-successor rejection, exact and competing commands, handover/internal
retry races, atomic rollback, immutable audit and orphan assignment prevention.
Exercise empty schema and released-schema upgrade with existing machine
assignments and retry history. Synthetic OIDC and isolated upstream invocation
fixtures do not establish real IdP or production provider readiness.
