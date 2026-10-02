# Human follow-up work

## TL;DR

Explicit exhausted-run escalation atomically opens one tenant-scoped `OPEN`
work item. Its case, run, escalation source, reason, and timestamps are durable
and immutable. The creation snapshot routes it to the immutable named
`access-restoration` queue. Only an authenticated actor with current tenant-wide
resolver evidence can retrieve it, list it in the oldest-first unclaimed inbox,
and acquire immutable ownership. A resolver can filter discovery by queue and
page through their active claimed work. Queue administration, priority,
reassignment, notifications, and completion are not implemented. The internal
resolver boundary supports revision-checked release and later claiming;
browser release remains deferred.

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

The browser can submit an explicit first-claim intent with the same
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

The route applies only the `0` to `1` transition. Changed command intent and
stale revision return `409` with distinct stable problem types; negative
revision returns `400`. Authentication, tenant binding, current resolver
authority, CSRF, and no-store rules match the existing browser claim route.
When browser sessions are disabled it returns
`503 browser-authentication-unavailable`. The existing browser claim route is
unchanged and must not be used for a later claim cycle.

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

Each row retains separate `workItem` and `claim` objects. The browser claim
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
and exhausted-retry decision. The execution view contains connector and timing
but omits provider-operation, authorization-consumption, grant, and idempotency
identifiers. The escalation view contains the retry-policy revision and exact
attempt ceiling but omits resolver and authority-evidence attribution.
Observation content is untrusted source data and clients must render it as text,
never executable markup. Provider subjects and tokens are excluded.

The server resolves the actor from the verified OIDC session. The item must be
`OPEN`, claimed by that actor, and visible under current tenant-wide resolver
authority. Absence, closure, different ownership, and lost authority all return
the same `404 resolver-follow-up-case-summary-not-found`; case and run records
are not read until this ownership check succeeds. The multi-query read runs in
one transaction so its case and run projections form one database view.

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
The browser revisioned claim route remains restricted to revision zero.

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
routing, automatic assignment, reassignment, priority, due time,
service levels, completion, cancellation, notification, semantic summaries,
supervisor workload views, general case search/detail, or other browser
mutations.
