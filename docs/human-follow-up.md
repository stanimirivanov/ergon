# Human follow-up work

## TL;DR

Explicit exhausted-run escalation atomically opens one tenant-scoped `OPEN`
work item. Its case, run, escalation source, reason, and timestamps are durable
and immutable. The creation snapshot routes it to the immutable named
`access-restoration` queue. Only an authenticated actor with current tenant-wide
resolver evidence can retrieve it, list it in the oldest-first unclaimed inbox,
and acquire immutable ownership. A resolver can filter discovery by queue and
page through their active claimed work. Queue administration, priority,
reassignment, release, notifications, and later lifecycle transitions are not
implemented.

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

The creation row, its initial queue, and first-owner claim are immutable. Later
changes must define attributable lifecycle events and may add a current-state
projection. This slice does not define queue administration, configurable
routing, automatic assignment, reassignment, release, priority, due time,
service levels, completion, cancellation, notification, summaries, supervisor
workload views, or a UI.
