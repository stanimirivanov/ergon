# Human follow-up work

## TL;DR

Explicit exhausted-run escalation atomically opens one tenant-scoped `OPEN`
work item. Its case, run, escalation source, reason, and timestamps are durable
and immutable. The creation snapshot routes it to the immutable named
`access-restoration` queue. Only an authenticated actor with current tenant-wide
resolver evidence can retrieve it, list it in the oldest-first unclaimed inbox,
and acquire immutable ownership. A resolver can filter discovery by queue and
page through their active claimed work. Queue administration, priority,
and notifications are not implemented. A separately enabled browser completion
command retires owned work without resolving the case. A separately enabled browser
command transfers current-owned work directly between authorized resolvers. The internal
resolver and browser boundaries support revision-checked release and later
claiming. Browser release remains disabled by default during rollout.

## Administrative completion foundation

One immutable completion fact ends active work without rewriting its OPEN
creation snapshot or final claim. Completed work is absent from shared/owned
views, current work/claim retrieval, private notes/context and all existing
ownership writes. Historical escalation-source lookup remains an opening record,
not active access. Case state, runtime state and accepted proof are unchanged.

M05-PR11a supplies the constrained audit table and post-lock active guards.
M05-PR11b adds the authenticated command below; M05-PR12 supplies its UI/user guide.
Deploy V20261023195734 and every guarded reader/writer before any producer.
After first use retain guards/schema/audit on rollback; never reinterpret OPEN
creation snapshots as currently active. See
[ADR 0065](decisions/0065-retire-administratively-completed-follow-up-work.md).

### Confirm administrative completion

POST `/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/completions`
with session CSRF and
`{"commandId":"<uuid>","claimId":"<uuid>","expectedOwnershipRevision":1}`.
The session supplies the actor. In one short READ COMMITTED transaction the
server locks the work row, then samples time/current tenant-wide resolver
authority and requires exact current ownership before appending a terminal fact.

First append returns 201; exact authorized replay returns 200 with identical
`commandId`, `workItemId`, `claimId`, `expectedOwnershipRevision`, `completedAt`
and database `recordedAt`. After uncertainty resend only the original command
ID/body. Historical replay requires the original actor's current resolver
authority, not active ownership; it restores no case/notes access. No authority
evidence, provider identity, case/run payload, text or proof is disclosed.

Hidden/absent work, another actor, lost authority or a different command on
completed work share `404 human-follow-up-completion-not-found`. Changed
same-actor same-command intent or stale owned coordinates is
`409 human-follow-up-completion-conflict`; nonpositive revision is
`400 invalid-human-follow-up-completion`. The default-off
`ERGON_HUMAN_FOLLOW_UP_COMPLETION_ENABLED` returns
`503 human-follow-up-completion-unavailable`, including replay. Disabled browser
sessions retain `503 browser-authentication-unavailable`. Other session/CSRF,
actor-binding and no-store rules match existing BFF commands.

No new migration. Deploy terminal schema and every guard before this producer;
enable only after compatible backend/UI deployment. Disable commands for rollback,
retain immutable audit/schema/guards, and never downgrade to OPEN-only readers.
Existing claim/release/handover/notes acknowledgement stays unavailable after
completion. See [ADR 0066](decisions/0066-complete-owned-follow-up-with-exact-recovery.md).

## Priority foundation

M05-PR13a adds immutable, tenant/work-scoped priority assignments under
[ADR 0067](decisions/0067-record-independent-follow-up-priority.md). LOW, NORMAL,
HIGH and URGENT are administrative metadata, not risk, authority, an SLA or a
runtime decision. No assignment means NORMAL at independent priority revision
zero. Each accepted set appends the next revision, including a repeated value
under a new command identity. Ownership/case/run revisions and queue ordering
are unchanged.

The schema serializes inserts with completion/ownership writers on the existing
work-row lock, then checks fresh terminal/current ownership and priority state.
It preserves exact claim/actor/RESOLVER-evidence attribution, command uniqueness
and immutable audit. Privileged SQL integrity is not authentication, current
authority or receipt-replay authorization.

PR13b supplies the default-off authenticated command below; PR13c supplies
active-work projections. PR14 UI/guide waits for both. These contracts must
retain oldest-first queue/cursor semantics and neutral access denial.
Deploy V20261024101559 before the producer; no backfill, table rewrite or new
index on existing rows is needed. Budget referenced-table DDL locks. Rollback
retains schema/audit and terminal guards while disabling the later producer.

### Assign current-owned priority

POST `/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/priority-assignments`
with session CSRF and
`{"commandId":"<uuid>","claimId":"<uuid>","expectedOwnershipRevision":1,"expectedPriorityRevision":0,"priority":"HIGH"}`.
Priority is exactly LOW, NORMAL, HIGH or URGENT. The session supplies the actor.
In one short READ COMMITTED transaction, lock work first, then sample time,
require current tenant-wide RESOLVER evidence and exact active ownership before
reading any receipt. A new intent must match the latest independent priority
revision; each accepted set advances it by one, including the same value.

First append returns 201; authorized exact replay returns 200 with identical
`commandId`, `workItemId`, `claimId`, `expectedOwnershipRevision`,
`expectedPriorityRevision`, `priorityRevision`, `priority`, `assignedAt` and
database `recordedAt`. Actor/authority evidence, provider identity, case/run
payload, notes and proof are excluded. Resend only the original ID/body after
uncertainty. An older receipt can be acknowledged after a later assignment,
but it neither reports current priority nor reinstates that older value.

Unlike completion/handover recovery, replay requires the original exact active
ownership and current authority. Release, handover and completion retire that
access. Absent/hidden/terminal work, another owner or lost authority shares
`404 human-follow-up-priority-not-found`. Stale owned coordinates/priority or
changed original intent returns `409 human-follow-up-priority-conflict` without
disclosing the winning receipt. Nonpositive ownership revision, negative or
exhausted (`Long.MAX_VALUE`) expected priority revision, or an invalid priority
returns `400 invalid-human-follow-up-priority`.

The default-off `ERGON_HUMAN_FOLLOW_UP_PRIORITY_ENABLED` returns
`503 human-follow-up-priority-unavailable`, including replay. Disabled browser
sessions retain `503 browser-authentication-unavailable`. Session, actor-binding,
CSRF and no-store rules match the existing commands. No new migration is needed:
deploy V20261024101559 and PR13c readers/PR14 UI before enabling the producer.
Rollback disables commands but retains immutable audit/schema/terminal guards.
Existing queue order, ownership, risk, runtime/case state and proof are unchanged.

## Direct owner handover

POST `/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/handovers`
with session CSRF and
`{"commandId":"<uuid>","claimId":"<uuid>","expectedOwnershipRevision":1,"recipientActorId":"<uuid>"}`.
The session supplies the sender. The exact current owner and proposed distinct
recipient must both have current tenant-wide resolver authority after the
work-item lock wait. An absent, foreign-tenant or ineligible recipient has the
same `409 human-follow-up-handover-recipient-unavailable` rejection. No directory
or provider identity is disclosed; recipients are explicitly supplied actor UUIDs.

The first transfer returns 201; exact authorized replay returns 200 with the
identical `commandId`, `workItemId`, `originalClaimId`,
`expectedOwnershipRevision`, `recipientActorId`, `successorClaimId`,
`ownershipRevision`, `handedOverAt` and database `recordedAt`. The new claim and
receipt commit with direct projection replacement: no shared-inbox exposure,
release event, case/run change or proof. Revision advances by two (1 to 3),
preserving claim/release parity; the unused intermediate revision is not a
missing event. Current recipients can read predecessor notes; former owners
cannot read notes or case context.

After uncertainty retry only the identical command ID/body, never a new intent.
The original sender needs current resolver authority, not continued ownership,
to recover this historical acknowledgement; it grants no renewed private access.
Absent/closed work, non-owners and missing sender authority share
`404 human-follow-up-handover-not-found`. Changed same-sender intent or stale
coordinates return `409 human-follow-up-handover-conflict`; self-transfer,
nonpositive revision or overflow returns `400 invalid-human-follow-up-handover`.
Other actors cannot recover the receipt. Disabling the default-off
`ERGON_HUMAN_FOLLOW_UP_HANDOVER_ENABLED` returns
`503 human-follow-up-handover-unavailable`; disabled browser sessions retain
`503 browser-authentication-unavailable`.

Deploy the additive migration and projection-aware backend before the UI;
enable only after compatible readers/writers and UI are deployed. The history
unique key needs an exclusive lock and index space; budget these against table
size. Rollback disables commands and retains audit/schema. See
[ADR 0064](decisions/0064-transfer-current-owned-follow-up-atomically.md).

## Internal notes

Current authorized owners can GET or POST
`/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/notes`.
POST uses session CSRF and
`{ "noteId": "<uuid>", "claimId": "<uuid>", "expectedOwnershipRevision": 1, "text": "Internal commentary" }`.
The server supplies author identity. Text is nonblank plain Unicode (at most
4000 code points), preserved exactly and treated as untrusted commentary, never
case evidence or proof. First append returns 201; exact same-intent replay is 200.
Do not mint a new note ID after an uncertain response.

GET returns `{ "items": [...], "truncated": false }`, newest first by
`recordedAt` then `noteId`, with at most 100 records; `truncated` explicitly
means older history was omitted. Each record contains note/claim IDs, ownership
revision, internal `authorActorId`, text, `authoredAt` and `recordedAt`.
It never discloses provider subject, tokens or authority evidence.

Missing/closed work, another owner and lost resolver authority share
`404 human-follow-up-notes-not-found`; stale ownership or changed intent is
`409 human-follow-up-note-conflict`; invalid text/revision is
`400 invalid-human-follow-up-note`. Session, actor-binding, CSRF, no-store and
disabled-mode behavior match other BFF routes. Replay requires current ownership
too. A later owner can read earlier authors' notes, but cannot replay their writes.
Notes do not change ownership, runtime state, evidence or case completion.
No editing, deletion, rich text, attachments, requester access, older-history
pagination or UI is included. See [ADR 0063](decisions/0063-record-owner-attributed-internal-notes.md).

## Creation and replay

Call the authenticated escalation command described in [resolution
runs](runs.md#escalate-exhausted-recovery). A successful first request returns
`201 Created`; `Location` identifies:

```text
/internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}
```

The response includes `followUpWorkItemId`, `followUpQueueKey`, and
`followUpStatus`. The current escalation path explicitly selects the
`access-restoration` queue. Run escalation, the run-state projection, and
work-item creation commit in one transaction. Repeating the escalation returns
`200`, the original identity, and the same location. One run and one escalation
event can each source at most one item.

## Retrieval and authority

Send `GET` to the returned location with a valid human bearer token. The token
must resolve to an actor in the path tenant, and that actor must have unexpired
tenant-wide `RESOLVER` evidence at lookup time.

The response contains the work-item, case, run, and escalation-event IDs;
`RETRY_ATTEMPT_LIMIT_REACHED` reason; immutable `queueKey`; `OPEN` status; and
application opening and database recording instants. Missing work and missing
resolver authority both return `404 human-follow-up-work-item-not-found` to
avoid existence disclosure. Missing or invalid authentication fails before
controller dispatch.

These are internal development endpoints, not a complete production resolver
authorization or data-disclosure boundary.

## Resolver inbox

An authenticated resolver can discover unclaimed open work with:

```text
GET /internal/v1/tenants/{tenantId}/human-follow-ups?limit=50
```

Add `queueKey=access-restoration` to select one exact named queue. Omitting it
preserves the all-queue inbox; a valid unknown key returns an empty page. Keys
must start with a lowercase letter and contain at most 63 lowercase letters,
digits, or hyphens. Invalid values return
`400 invalid-human-follow-up-queue`.

Only unclaimed `OPEN` items appear. They are ordered by `openedAt` and then
`workItemId`, oldest first. `limit` defaults to 50 and must be between 1 and
100. When another page exists, copy both values from `nextCursor` into
`afterOpenedAt` and `afterWorkItemId` on the next request. Supplying only one
cursor value returns `400 invalid-human-follow-up-page`. Retain the same queue
filter across all pages because the cursor does not encode it.

The inbox uses current tenant-wide resolver evidence. A caller without that
evidence receives an empty page so the response does not reveal whether the
tenant contains work. A named queue is a shared discovery route, not resolver
assignment or authorization, and the inbox provides no total count.

## Browser resolver inbox

The confidential browser session exposes the same application query at:

```text
GET /bff/v1/tenants/{tenantId}/human-follow-ups?limit=50
```

It accepts the same `queueKey`, `limit`, `afterOpenedAt`, and `afterWorkItemId`
parameters and preserves the internal inbox's filtering, validation, authority,
ordering, and non-disclosure semantics. Its browser-specific response contains
the immutable work source, queue, status, and timestamps plus an optional exact
next cursor. It contains no provider subject, token, authority evidence, or
total count.

Unauthenticated requests return `401 browser-authentication-required` with the
local BFF sign-in path. A verified identity not registered in the requested
tenant returns `403`; missing current resolver authority returns an empty page.
When browser sessions are disabled, the route returns
`503 browser-authentication-unavailable`. See
[ADR 0037](decisions/0037-expose-resolver-inbox-through-browser-session.md).

## Browser claim

After obtaining the authenticated session CSRF token described in
[authentication](authentication.md#browser-session-boundary), claim visible
work with:

```text
POST /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims
X-CSRF-TOKEN: {opaque session token}
```

The server resolves the OIDC session to the tenant actor; the browser never
supplies an actor ID. The first claim returns `201`; a retry by the same resolver
returns `200` with the original immutable claim. The response contains
`claimId`, `workItemId`, `claimedAt`, and `recordedAt`. It excludes provider
identity, resolver actor identity, authority-evidence attribution, and tokens.

An authenticated request without the correct session token returns
`403 invalid-browser-csrf-token` before claim application code runs. An expired
or absent session returns `401 browser-authentication-required`. Missing current
resolver authority returns `403 human-follow-up-resolver-authority-required`,
absent work returns `404 human-follow-up-work-item-not-found`, and a competing
resolver returns `409 human-follow-up-already-claimed`.

Same-resolver replay makes this specific command safe to repeat after an
ambiguous response. It does not establish a blanket automatic-retry policy for
other mutations. See
[ADR 0038](decisions/0038-protect-browser-commands-with-session-csrf-tokens.md).

### Revision-checked browser claim

The browser can submit an explicit revision-checked claim intent with the same
session-bound CSRF token:

```text
POST /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claim-commands
X-CSRF-TOKEN: {opaque session token}
Content-Type: application/json

{"commandId":"<UUID>","expectedOwnershipRevision":0}
```

The BFF resolves the actor from the OIDC session; the request contract has no
actor or authority-evidence fields. A new first claim returns `201`; an
exact retry by the same resolver, command ID, and expected revision returns
`200` with the original `commandId`, resulting `ownershipRevision`, and nested
browser-safe claim. The nested claim has only `claimId`, `workItemId`,
`claimedAt`, and `recordedAt`; neither response exposes the provider subject,
resolver actor, authority evidence, or tokens. Command IDs are scoped to one
tenant and work item.

The route also accepts the current revision of unowned work returned by the
browser inbox after a release. Every later cycle requires a fresh command ID;
the older `/claims` route remains first-cycle-only. Changed command intent and
stale revision return `409` with distinct stable problem types; negative
revision returns `400`. Authentication, tenant binding, current resolver
authority, CSRF, and no-store rules match the existing browser claim route.
When browser sessions are disabled it returns
`503 browser-authentication-unavailable`.

### Browser release and revision reads

Each row in the authorized browser shared inbox includes `ownershipRevision`
(`0` before first claim, `2` after first release, and so on). Each owned-work
row includes the current revision on its `workItem`. A resolver can therefore
recover the expected state after a page reload; these values are not
authorization grants and may become stale before submission.

With a current claim ID from the owned-work row, release using the session
CSRF token and that row's revision:

```text
POST /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/{claimId}/release
X-CSRF-TOKEN: {opaque session token}
Content-Type: application/json

{"expectedOwnershipRevision":1}
```

A new release returns `201`; an exact replay by the same actor and expected
revision returns `200` with the original `claimId`, `workItemId`, resulting
`ownershipRevision`, `releasedAt`, and `recordedAt`. The browser sends no
actor or authority-evidence IDs and receives none. Release needs current
tenant-wide resolver authority even on replay. An absent, inactive, or
different owner's claim returns the same non-disclosing `404`; stale revision
returns `409`, negative revision `400`. Release remains disabled by default
and returns `503 human-follow-up-release-unavailable` until the upgraded
deployment enables it. Disabled browser sessions instead return
`503 browser-authentication-unavailable`. A successful release returns work
to its original shared queue; a later resolver uses a fresh revision-checked
claim command with the revision from the inbox. See
[ADR 0043](decisions/0043-define-human-follow-up-release-semantics.md).

## Browser resolver-owned work

An authenticated browser-session resolver can recover active claims with:

```text
GET /bff/v1/tenants/{tenantId}/human-follow-ups/owned?limit=50
```

The route preserves the internal owned-work query's current-authority,
oldest-claim-first ordering, `1..100` limit, and paired `afterClaimedAt` plus
`afterClaimId` cursor semantics. A resolver without current authority receives
an empty page; the response provides no total count and never reveals another
resolver's work.

Each row retains separate `workItem` and `claim` objects. The `workItem`
includes the current ownership revision needed for an exact release. The browser claim
contains only `claimId`, `workItemId`, `claimedAt`, and `recordedAt`; resolver
actor identity, authority-evidence attribution, provider identity, and tokens
are omitted. Invalid pagination returns
`400 invalid-resolver-owned-human-follow-up-page`. When browser sessions are
disabled, the route returns `503 browser-authentication-unavailable`. See
[ADR 0039](decisions/0039-expose-resolver-owned-work-through-browser-session.md).

## Browser owned-work case summary

An authenticated resolver can inspect one active item they own with:

```text
GET /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary
```

The response combines the work item's queue, reason, and timing with the case
header, pinned contract revision, attributable source observations, immutable
resolution-run inputs, current `ESCALATED` run state, failed connector outcome,
and exhausted-retry decision. `runHistory.attempts` orders the linked durable
attempts and their capability-result and retry transitions; it is not a live
trace of tool spans, latencies, or cost. Each attempt identifies its run,
attempt number, predecessor, recorded start, current state/version/update,
capability-result event and timing, and any retry event and successor. The
final attempt is the summary's escalated run. `outcomeProof` names the fact and
expected value from the pinned contract. Because this handoff run is
`ESCALATED`, not `VERIFYING`, its assessment is `NOT_ASSESSED` with reason
`RUN_NOT_VERIFYING`: this does not mean the outcome condition was disproven.
The execution view contains connector and timing but omits provider-operation,
authorization-consumption, grant, and idempotency identifiers. The escalation
view contains the retry-policy revision and exact attempt ceiling but omits
resolver and authority-evidence attribution.
Observation content is untrusted source data and clients must render it as text,
never executable markup. Provider subjects and tokens are excluded.

The server resolves the actor from the verified OIDC session. The item must be
`OPEN`, claimed by that actor, and visible under current tenant-wide resolver
authority. Absence, closure, different ownership, and lost authority all return
the same `404 resolver-follow-up-case-summary-not-found`; case and run records
are not read until this ownership check succeeds. The multi-query read runs in
one transaction and validates relationships across its durable records. At the
default isolation level, the transaction alone does not guarantee one
point-in-time snapshot of every projection.

The failed receipt must match the run's case, policy, step, and capability. The
escalation must match the work item's source event, reason, attempt, and opening
instant and cannot predate connector completion. Contradictory durable records
fail as internal invariant violations rather than being shown to the resolver.

The route is read-only, requires no CSRF token, uses no-store response headers,
and returns `503 browser-authentication-unavailable` when browser sessions are
disabled. See
[ADR 0040](decisions/0040-expose-owned-follow-up-case-summary-through-browser-session.md).
The failed-execution and retry-handoff extension is recorded in
[ADR 0041](decisions/0041-expose-failed-execution-and-escalation-context.md).
The run-history and pinned-proof extension is recorded in
[ADR 0044](decisions/0044-expose-owned-run-history-and-proof-requirement.md).

## Claim and replay

An authenticated resolver acquires ownership with:

```text
POST /internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims
```

The first claim returns `201 Created` and a `Location` for the immutable claim.
The response retains the claim and work-item IDs, resolver actor, exact
authority-evidence ID, claim instant, and database recording instant. Repeating
the command as the same resolver returns `200`, the same claim, and the same
location. A different resolver receives
`409 human-follow-up-already-claimed` and cannot replace the owner.

Claim creation, competing-claim detection, and replay run in one transaction
under a work-item row lock. Current resolver authority is required before item
lookup; absence returns `403 human-follow-up-resolver-authority-required`
without revealing whether the item exists. A claimed item remains lifecycle
`OPEN` but leaves the shared inbox.

Send `GET` to the claim location to retrieve its durable attribution. Missing,
mismatched, and currently unauthorized coordinates all return
`404 human-follow-up-claim-not-found`.

### Revision-checked claim command

An internal bearer-authenticated resolver can also submit a durable command
identity and expected ownership revision:

```text
POST /internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claim-commands
Content-Type: application/json

{"commandId":"<UUID>","expectedOwnershipRevision":0}
```

The first claim moves revision `0` to `1`. After a release, a new command may
claim the unowned work at its current revision and receives a new immutable
claim ID. The server checks current resolver authority before revealing the
work item, then locks it and
compares its current ownership revision. A new command returns `201` with the
command ID, resulting revision, and nested immutable claim. An exact retry by
the same actor with the same command ID and expected revision returns `200`
with the original result; this receipt is stored in the same transaction as
the claim and ownership event. Command IDs are scoped to a tenant and work
item. The response `Location` identifies the claim.

A reused command ID with different actor or expected revision returns
`409 human-follow-up-claim-command-conflict`. A stale expected
revision returns `409 human-follow-up-ownership-revision-conflict`; a negative
revision returns `400 invalid-human-follow-up-claim-command`. Neither conflict
reveals another resolver's identity. Current resolver authority is required
even for replay. The legacy claim routes remain first-cycle-only: after a
release they return `409` rather than interpreting an old retry as new intent.
The browser revisioned claim route also accepts the current unowned revision
returned by the authorized browser inbox.

### Release current ownership

An internal bearer-authenticated resolver releases only their active claim:

```text
POST /internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/{claimId}/release
Content-Type: application/json

{"expectedOwnershipRevision":1}
```

The server resolves the actor and requires current tenant-wide resolver
authority, then serializes the command on the work-item lock. The claim ID and
expected revision must identify the active owner. A first release returns
`201` with `claimId`, `workItemId`, `resolverActorId`, the accepted
`authorityEvidenceId`, resulting `ownershipRevision`, `releasedAt`, and
`recordedAt`. An exact retry by the same actor and expected revision returns
`200` with that original receipt, even if ownership has since changed.
Negative revision returns `400 invalid-human-follow-up-release-command`;
absent, inactive, or differently owned claims return the same non-disclosing
`404 human-follow-up-release-not-found`; stale revision returns
`409 human-follow-up-ownership-revision-conflict`.

Release is disabled by default and returns
`503 human-follow-up-release-unavailable` until
`ERGON_HUMAN_FOLLOW_UP_RELEASE_ENABLED=true` is set for the upgraded deployment.

Release appends an immutable event and clears only current ownership. The work
stays `OPEN` in its original named queue and becomes discoverable in the shared
inbox. A later resolver must use a new claim command ID and the current
revision; first-claim history is not rewritten. Historical claim retrieval is
an audit read, not evidence of current ownership. See
[ADR 0043](decisions/0043-define-human-follow-up-release-semantics.md).

## Resolver-owned work

An authenticated resolver can recover their active claimed work with:

```text
GET /internal/v1/tenants/{tenantId}/human-follow-ups/owned?limit=50
```

Items are ordered by `claimedAt` and then `claimId`, oldest first. Each result
contains separate nested `workItem` and `claim` values so creation and ownership
facts retain their meanings. `limit` defaults to 50 and must be between 1 and
100. Copy both `afterClaimedAt` and `afterClaimId` from `nextCursor` to request
the next page; supplying only one returns
`400 invalid-resolver-owned-human-follow-up-page`.

Only `OPEN` work claimed by the authenticated actor appears. The actor must
also have current tenant-wide resolver evidence. Another resolver, or an actor
without current resolver evidence, receives an empty page. This view does not
grant access to other resolvers' workload and provides no stable total count.

## Deliberate limits

The creation row, its initial queue, and first-owner claim are immutable.
Claims and releases append ownership events and advance the current-ownership
projection in the same transaction. An older application instance can still
insert a first claim through the mirror trigger, but it cannot interpret a
released or later-claimed item. The inbox and owned-work queries use the
projection; historical claim retrieval remains an audit read.

The additive migration backfills existing first claims and installs the
first-claim mirror before switching readers. Keep
`ERGON_HUMAN_FOLLOW_UP_RELEASE_ENABLED=false` until all ownership readers and
writers run this lifecycle version. Before the
first release, an older binary can ignore the expanded schema; after release,
recovery must roll forward because it cannot interpret current ownership.
Assess the claim-table backfill and trigger-install lock against production
table size before deployment.

This slice does not define queue administration, configurable
routing, automatic assignment, administrative reassignment, priority, due time,
service levels, case closure, reopening, cancellation, notification, semantic summaries,
supervisor workload views, general case search/detail, or other browser
mutations.
