# Human follow-up work

## TL;DR

Explicit exhausted-run escalation atomically opens one tenant-scoped `OPEN`
work item. Its case, run, escalation source, reason, and timestamps are durable
and immutable. Only an authenticated actor with current tenant-wide resolver
evidence can retrieve it or list it in the oldest-first resolver inbox.
Assignment, named queues, priority, notifications, and later lifecycle
transitions are not implemented.

## Creation and replay

Call the authenticated escalation command described in [resolution
runs](runs.md#escalate-exhausted-recovery). A successful first request returns
`201 Created`; `Location` identifies:

```text
/internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}
```

The response includes `followUpWorkItemId` and `followUpStatus`. Run escalation,
the run-state projection, and work-item creation commit in one transaction.
Repeating the escalation returns `200`, the original identity, and the same
location. One run and one escalation event can each source at most one item.

## Retrieval and authority

Send `GET` to the returned location with a valid human bearer token. The token
must resolve to an actor in the path tenant, and that actor must have unexpired
tenant-wide `RESOLVER` evidence at lookup time.

The response contains the work-item, case, run, and escalation-event IDs;
`RETRY_ATTEMPT_LIMIT_REACHED` reason; `OPEN` status; and application opening and
database recording instants. Missing work and missing resolver authority both
return `404 human-follow-up-work-item-not-found` to avoid existence disclosure.
Missing or invalid authentication fails before controller dispatch.

These are internal development endpoints, not a complete production resolver
authorization or data-disclosure boundary.

## Resolver inbox

An authenticated resolver can discover open work with:

```text
GET /internal/v1/tenants/{tenantId}/human-follow-ups?limit=50
```

Items are ordered by `openedAt` and then `workItemId`, oldest first. `limit`
defaults to 50 and must be between 1 and 100. When another page exists, copy
both values from `nextCursor` into `afterOpenedAt` and `afterWorkItemId` on the
next request. Supplying only one cursor value returns
`400 invalid-human-follow-up-page`.

The inbox uses current tenant-wide resolver evidence. A caller without that
evidence receives an empty page so the response does not reveal whether the
tenant contains work. The inbox is a read model over open work, not a named
queue or assignment, and it provides no total count.

## Deliberate limits

The creation row is immutable. Later changes must define attributable lifecycle
events and may add a current-state projection. This slice does not define
named queues, routing, assignment, ownership, priority, due time, service
levels, claiming, completion, cancellation, notification, summaries, or a
resolver UI.
