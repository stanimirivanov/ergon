# ADR 0076: Open requester-owned cases atomically

- Status: Accepted
- Date: 2026-10-27
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR23 needs authenticated intake without leaving an orphan case after a lost
response or granting approval authority merely to disclose progress. Existing
case events and requester bindings already retain the necessary immutable facts.
PR23a establishes the application/storage behavior; PR23b adds its browser boundary.

## Decision

Use the client-selected tenant-scoped case UUID as stable intake identity. The
application caller supplies a verified registered actor. Hold the same advisory
stream lock as existing case writers before looking up the first event. In one
required transaction, append/project the opening and insert its creator binding
with trusted `requester-intake:v1` provenance. No HTTP route is introduced in PR23a.

Recover only that producer's opening with the same actor, goal and observation.
Preserve existing domain trimming and UTF-16 limits (500/8000); additionally reject
NUL and malformed Unicode before storage. Comparison uses normalized text, not
raw whitespace. The receipt contains original normalized input and occurrence/
recording times, never current status or authority. Read only the first immutable
event, not the growing timeline. Later closure/events do not change recovery.

Different content/creator, unbound legacy cases and operator-bootstrapped cases
share a diagnostic-free conflict; never adopt them or return their private data.
Tenant-safe actor foreign keys reject missing/foreign actors and roll back all
new events, projection and binding. No contract selection, run start or grant.

## Alternatives considered

Generating a fresh server case UUID on every retry permits duplicate cases.
A separate idempotency table duplicates the immutable opening and binding without
adding a required guarantee. Reusing approval evidence invents ownership and
authority. Treating any existing same-owner case as intake would adopt legacy
material; producer provenance deliberately excludes that path.

## Consequences

Existing requester progress immediately consumes new bindings. Existing writers
remain unchanged and continue producing unowned cases. Trusted application callers
must resolve identity; the use case itself is not authentication. PR23b must
enforce the separate requester client, tenant registration and CSRF before exposing
the service. PR24 UI/guide waits for that backend review/merge.

## Compatibility and migration

No migration, event schema, new dependency or existing endpoint change. Deploy the
existing V20261025051735 ownership schema first. The additive service is dormant
at browser boundaries until PR23b. Application rollback preserves immutable case
history/bindings and existing protected reads; do not delete audit to undo intake.

## Security and operations

Browser actor IDs and observation text cannot establish ownership. Intake
provenance is written by the trusted adapter, never caller input. Requests with
different intent receive no original owner/text. No remote I/O is held inside
transactions. Case events and bindings retain sensitive untrusted text/attribution
under existing database access controls. Runtime accounts must remain private;
application tenant predicates are not RLS. Locks serialize one tenant/case; hash
collisions may add contention but never authorize access. Retained rows generate
ordinary event/projection/index/WAL growth; no backfill or new index is required.

## Validation

PostgreSQL tests cover atomic visibility/rollback, tenant-safe keys, concurrent
whole-winner recovery, changed intent/actor, legacy collisions, invalid Unicode
and immutable recovery after later events/closure. CI owns compilation, formatting,
static analysis and the complete reactor on a busy development host. A deferred
local check is not passing evidence; PR23b owns browser/session/CSRF tests.
