# ADR 0071: Authorize requester information commands

- Status: Accepted
- Date: 2026-10-25
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

ADR 0070 supplies immutable storage but no current-access decision. PR19b must
attribute deliberate questions and recover uncertain writes without granting
requester ownership, delivery, approval or continuing receipt access.

## Decision

Expose only POST at the work item's BFF information-requests resource. The
verified workbench session supplies the author; the immutable case binding
supplies the recipient. Input contains requestId, claimId, expectedOwnershipRevision
and exact question text, never recipient identity or caller timestamps.

In one READ COMMITTED transaction lock active work, require current owner and
tenant-wide resolver authority, match claim/revision, then lock the OPEN explicitly
bound case. Recheck authority after case-lock and unique-identity waits. Work-then-case
is the lock order; no remote calls or run locks occur. Failure rolls back the write.

First creation returns 201. Exact authorized replay returns the original 200
receipt; every intent coordinate except the server timestamp must match.
Changed intent or stale owned coordinates return 409; hidden, closed, unbound,
unowned or expired-authority targets share one neutral 404, including replay.
Invalid text/revision returns 400. Session/CSRF, requester-mode denial and no-store
remain the existing BFF rules.

## Alternatives considered

Historical receipt access after ownership loss would disclose private questions.
Client-selected recipients bypass explicit binding. Treating questions as notes,
observations or delivered messages conflates distinct privacy and trust meanings.

## Consequences

No case/run/ownership revision changes, connector call, response or notification
occurs. PR19c adds protected reads; PR20 UI and user guide follow backend merges.
Receipt possession grants no access. Reuse original intent after uncertainty;
never automatically replace its UUID.

## Compatibility and migration

Deploy ADR 0070's V20261025141955 first. No new migration or dependency.
Existing contracts remain unchanged. Block the POST at ingress or stop its
deployment for behavioral rollback; preserve schema and immutable audit.

## Security and operations

The receipt contains request/case/claim/revision, internal author, question and
authoring/recording instants, not recipient/provider identity or authority evidence.
Questions remain untrusted plain text and must stay out of logs/model instructions.
Privileged SQL integrity is not application authorization or RLS.

## Validation

Real PostgreSQL/HTTP tests cover exact/conflicting replay, release concurrency,
unavailable targets, atomic rollback on post-wait authority expiry, session/CSRF
and requester-mode denial. Existing storage/migration tests remain applicable.
Repository policy, static checks and full reactor verification are required.
