# ADR 0052: Finalize reviewed proof through the browser

- Status: Proposed
- Date: 2026-10-15
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The user approved current supervisors with current tenant-wide RESOLVER authority
accepting exact reviewed matching outcome proof. A MATCHING read is mutable;
existing internal acceptance has neither browser attribution nor reviewed-intent
preconditions. Exposing it directly would silently accept replacement evidence.

## Decision

Add a separate session/CSRF-protected proof-finalization command, default off.
First application requires current supervision, current resolver authority,
unchanged supervision/run revisions, the exact assessed case version and selected
fact/source identities. Reuse the existing evaluator and atomic proof completion.
Reassess before acceptance; stale or pending proof conflicts without substitution.
Lock run, tenant/command and runtime state in that order; case append remains
optimistic. Recheck current resolver evidence after waits and assessment. No remote
call enters the transaction. Concurrent case changes roll back the whole operation.
Pass one acceptance instant through authority validation and proof/case completion;
retain case-event nanoseconds rather than flooring acceptance before a new binding.
Resolver lookup uses the attestation store's microsecond boundary precision.

Commit case closure, accepted-proof run event/projection and immutable browser
receipt together. Receipt FKs bind exact accepted evidence, supervision epoch and
resolver attestation. The browser chooses neither actor nor attestation. Exact
replay requires the original actor/run/intent and current resolver authority, not
continued supervision or historical attestation validity. It acknowledges the
original result and cannot close twice. Never adopt an internal acceptance as a
new browser receipt. No automatic command retry is permitted.

## Alternatives considered

### Expose internal acceptance unchanged

- Benefits: no additional command protocol or receipt table.
- Costs and risks: missing attribution and reviewed-evidence fencing.
- Reason not selected: a later match must require a new explicit review.

### Require requester approval or permit any resolver

- Benefits: reuse existing approval authority or reduce assignment friction.
- Costs and risks: changes the approved responsibility boundary.
- Reason not selected: proof finalization is narrowly the current supervisor's
  resolver-authorized act, not a capability approval prerequisite.

## Consequences

### Positive

Operators can complete the reviewed outcome with attributable, replay-safe proof
and verified case closure without repeating the connector action.

### Negative

The protocol and append-only receipt add compatibility and retention obligations.
Assessment still loads complete case history. Browser response loss is uncertain.

### Neutral or follow-up

Only the existing account-access condition is supported. General steering,
leases, retries, forced closure, connectors and compensation are unchanged.

## Compatibility and migration

Deploy V20261015120000 and upgraded backend before enabling
`ERGON_RUN_PROOF_FINALIZATION_ENABLED`; it defaults false. The supporting UNIQUE
build scans/locks existing acceptances; size disk/WAL and lock time beforehand.
No backfill. Older binaries ignore the additive receipt table and existing
internal acceptance remains compatible. Disable commands for application rollback;
retain receipts, proof and closed cases. Do not reverse durable decisions.
Console reads add `proofFinalizationSupported`; omission is older-server
unsupported, false is disabled rollout. It is not a promise of current eligibility.

## Security and operations

The BFF resolves identity from the verified session and requires CSRF. Private
absence and foreign receipts remain neutral. Safe no-store problems distinguish
conflict, invalid intent and disabled rollout. Authority and internal event IDs
remain private. Historical receipts never authorize disclosure; fresh Console
reads retain current authority and supervision gates, including terminal reads.
No new endpoint is exposed under the internal ingress. Commands remain disabled
until the operator deliberately enables the reviewed policy.

## Validation

Real PostgreSQL tests cover exact replay, private attribution, stale versions and
identities, later matching/mismatching evidence, supervision/authority denial,
CSRF/session, atomic rollback, competing confirmations, internal acceptance
coexistence and upgrade of existing accepted proof. UI tests cover strict receipt
attribution, no retries, uncertainty, disclosure withdrawal, fresh terminal reads
and accessible confirmation. The executable guide is explicitly synthetic, not a
deployed identity/database/connector verification.
