# ADR 0074: Authorize requester answer commands

- Status: Accepted
- Date: 2026-10-26
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

ADR 0073 supplies immutable answers but no authenticated write. PR21b must recover
uncertainty without treating a question UUID, resolver role or receipt as authority.

## Decision

Expose POST at the requester's scoped case/question responses resource. Input
contains responseId and exact answer only; the verified tenant session supplies
actor identity and the server supplies timestamps. Fixed requester OIDC audience,
CSRF and no-store apply; workbench sessions are denied and disabled browsers return 503.

In one READ COMMITTED application transaction lock the OPEN explicitly owned
case and verify the question belongs to it. Hold the case lock through unique-key
waits and winner comparison. Acquire no work/run lock: resolver lifecycle changes
do not change requester ownership. PostgreSQL re-evaluates OPEN after closure waits.

Compare response UUID, question UUID, exact text and derived case/requester before
acknowledging. Regenerated server time is not retry intent; retain original stored
timestamps. First write is 201, exact authorized replay 200, changed intent 409,
invalid text 400. Missing, unbound, foreign, wrong-question and closed targets
share a neutral 404, including replay. Conflicts disclose no winner payload.

Return only responseId, requestId, caseId, answer, respondedAt and recordedAt;
never actor/provider identity, work, claims or authority evidence.

## Alternatives considered

Resolver authority is the wrong audience. Receipt-based recovery outlives access.
Unlocked OPEN checks race closure. Work locking after case reverses existing lock
order. Automatic fresh UUID retry conceals changed intent.

## Consequences

Answers remain untrusted source material, not facts, consent, approval or proof.
No observation stream, case/run/ownership revision, notification or connector changes.
PR21c protected reads precede PR22 UI and executable guide; corrections need a new question.

## Compatibility and migration

Deploy V20261026102606 first. No schema, dependency or new flag: existing
default-disabled browser authentication and requester deployment opt-in remain.
The POST allowlist is exact; existing GETs and resolver routes are unchanged.
Block this POST at ingress or roll back application code to disable new writes,
retaining immutable schema/audit and compatible readers.

## Security and operations

Verified actor resolution precedes the application call. Selectors are not
authority. Keep answers and private storage errors out of logs/problem details.
Comparisons fail transactionally. Case locks contain no remote I/O; storage
failures propagate rather than falsely acknowledging uncertainty. Browser
cancellation cannot prove rollback; retain original intent for explicit recovery.

## Validation

Unit tests cover denial, invalid text, intent/attribution mismatches and defects.
PostgreSQL/HTTP tests cover exact/concurrent recovery, tenant isolation, narrow
receipts, CSRF/audience denial and actual case-lock waits withdrawing both first
write and replay after closure. Existing storage/migration tests remain required.
Report policy, formatting, static and complete reactor checks independently.
