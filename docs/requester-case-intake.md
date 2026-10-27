# Requester case intake foundation

## TL;DR

M05-PR23a opens a case and binds its verified creator atomically through an
application service. Reusing the same tenant/case UUID recovers the original
normalized intent, not current state. No browser endpoint is exposed yet; PR23b
adds authentication/CSRF and PR24 adds UI and the executable user guide.

## Identity and recovery

`RequesterCaseIntakeService.open` requires a registered, server-verified tenant
actor. It does not authenticate callers. The stable case UUID is a recovery key,
not authority. Goal and observation use existing domain trimming and UTF-16 limits
of 500 and 8000 code units; NUL and malformed Unicode are rejected. Valid surrogate
pairs are preserved. Callers retain the original command after uncertainty.

The historical receipt contains case identity, normalized goal/initial observation,
opening time and first-event recording time. It contains no current lifecycle,
runtime, contract, proof or approval assertion. Replays require the same actor and
normalized input, return the original receipt and append nothing, even after later
events or case closure. Fresh protected reads independently establish visibility.

Foreign actors, changed text and legacy/bootstrapped openings receive the same
conflict without original private content. Existing cases are never adopted.
Intake attribution is not REQUESTER approval evidence and creates no grant.

## Atomic storage and rollout

The application owns one required transaction: acquire the existing tenant/case
stream lock, check the immutable opening, append/project if absent, then insert
`case_requesters` with trusted producer provenance `requester-intake:v1`. The
adapter owns that marker; it is not input. A binding/FK failure rolls back every
write. Concurrent equal commands share one whole winner; different intent fails.
Recovery reads only stream version one, not a growing history or current projection.

No new schema, index, external dependency or feature flag. Existing ownership
migration V20261025051735 is required. Existing legacy writers remain unchanged.
Normal row/index/WAL growth and per-case lock contention apply. Retain immutable
history and bindings on rollback. Database accounts remain private; neither this
service nor SQL tenant predicates authorize direct browser database access.

## Verification and remaining boundary

`RequesterCaseIntakeIntegrationTest` exercises real PostgreSQL creation, rollback,
concurrency, tenant isolation, legacy conflicts, text constraints and recovery after
later lifecycle changes. Run it through the existing CI PostgreSQL stage,
`python tools/ci/verify.py postgres`, after its compilation/unit stages; complete
baseline remains `./mvnw -B -ntp verify`. Busy-host deferrals are recorded in the PR.

PR23b must resolve the requester session/tenant actor, enforce CSRF and fail closed
for other audiences before exposing any command. No new browser surface, discovery,
uploads, notifications, contract selection, run start or approval authority is
included here. See [ADR 0076](decisions/0076-open-requester-owned-cases-atomically.md)
and [protected progress](requester-case-progress.md).
