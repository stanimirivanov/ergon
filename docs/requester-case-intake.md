# Requester case intake

## TL;DR

POST `/bff/v1/tenants/{tenantId}/requester-cases` in the separate requester BFF
opens a case and binds its verified creator atomically. Reusing the same tenant/
case UUID recovers original normalized intent, not current state. Session/CSRF
and tenant registration are required. PR24 UI and executable guide follow backend
review/merge.

## Browser contract

Send JSON `caseId` (stable UUID), `goal` and `initialObservation`. The server
resolves the creator from the fixed requester OIDC session, never caller fields.
Unknown members are ignored, including actor/authority hints. Creation returns
201; same-creator normalized recovery returns 200. Both no-store receipts contain
exactly `caseId`, `goal`, `initialObservation`, `openedAt`, `recordedAt`, with UTC
instant timestamps. No status, stream version, actor, approval or source timeline.

Problems are diagnostic-free: 409 `urn:ergon:problem:requester-case-intake-conflict`
for conflicting/legacy intent; 400 `urn:ergon:problem:invalid-requester-case-intake`
for malformed JSON/UUID or invalid input. Arbitrary storage/codec/identity failures
are not input rejection. Uncertainty is not permission to replace intent: retain
the original UUID/text, retry only explicitly and compare the historical receipt.
Neither acknowledgement nor a successful recheck of progress creates a new intent.

Missing sessions use the existing 401/sign-in contract; unregistered, wrong-tenant
or rejected identities use existing 403 problems. Missing/foreign-session CSRF is
403 `urn:ergon:problem:invalid-browser-csrf-token`. Workbench sessions cannot use
intake, unsupported methods remain denied and there is no collection GET. Default
browser-disabled mode returns authentication-unavailable 503. See
[ADR 0077](decisions/0077-authorize-requester-case-intake.md).

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

PR23b's HTTP/session/CSRF tests run on CI; local non-container tests distinguish
typed input rejection from unexpected defects. Disable new intake by blocking the
collection POST or rolling back its adapter, retaining immutable audit and reads.
No discovery, uploads, notifications, contract selection, run start or approval
authority. Deployment payload/rate controls and live OIDC/two-origin acceptance
remain operational gaps. See [ADR 0076](decisions/0076-open-requester-owned-cases-atomically.md)
and [protected progress](requester-case-progress.md).
