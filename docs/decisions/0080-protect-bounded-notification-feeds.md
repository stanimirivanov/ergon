# ADR 0080: Protect bounded notification feeds

- Status: Accepted
- Date: 2026-10-28
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

PR25a/b create immutable prospective deliveries. PR25c must make them usable
without disclosing source text, mixing browser audiences or restoring target
authority from a historical receipt. A recent feed is not a complete work queue.

## Decision

Expose GET `/bff/v1/tenants/{tenantId}/notifications` through the configured browser
deployment only. Require its exact OIDC client and registered tenant actor.
The requester GET allowlist adds this exact route; the controller independently
checks the configured registration for both audiences. Bearer/non-OIDC identities
and other clients are rejected. Caller actor/audience/limit hints have no effect.

Return `{items, truncated}` with at most 50 newest deliveries and no cursor, total,
unread count or read acknowledgement. Each item has `kind`, `sourceId`, `target`
(`kind`, `id`) and `deliveredAt`. Identity is the full kind/source/target tuple:
handover command IDs are work-scoped. Sort descending by delivery time, kind with
C collation, source UUID and target UUID. `truncated` means omitted older entries,
not actionable/unread work. Refresh rechecks the feed; it is not pagination.

Requester feeds include INFORMATION_REQUESTED targeting REQUESTER_CASE, derived
from immutable case requester bindings and requiring no approval authority.
Workbench feeds include FOLLOW_UP_HANDED_OVER and INFORMATION_ANSWERED targeting
OWNED_HUMAN_FOLLOW_UP. Require fresh unscoped resolver authority before and after
retrieval, including after waits; absence returns diagnostic-free 403
`urn:ergon:problem:human-notifications-forbidden`, not an empty feed. Unknown actors
and invalid identities retain existing BFF failures. Responses/errors are no-store;
disabled browser configuration exposes the established 503 unavailable problem.

Each feed uses one SQL statement and immutable recipient/source joins. Do not
filter by current ownership, lock targets, return source text/actors/evidence,
mutate delivery or implicitly open a target. Historical alerts can remain after
release, completion or closure. Existing protected target APIs must independently
reacquire context; a retained alert can lead to unavailable access.

## Alternatives considered

Client-selected audience/recipient invites scope bypass. Returning full source
previews broadens disclosure after ownership loss. Filtering alerts by current
target authority rewrites historical delivery meaning and does not eliminate
navigation races. A total/unread count, cursor or acknowledgement store adds a
separate protocol not required by this bounded recent feed.

## Consequences

Application policy owns bounds and authority; adapters own SQL, fixed-client OIDC
and wire mapping. Source-specific storage remains private. No new dependency,
event framework, worker, capability grant or remote call. PR26 UI/guide follows
backend review/merge and must retire private state and fetch targets afresh.

## Compatibility and migration

Additive browser contract, selected by existing deployment/session configuration.
Deploy PR25a/b delivery schemas first. The new migration adds recipient-prefix
indexes over case requester bindings and CLAIMED ownership history for these
actual joins; existing question history, response uniqueness and notification
primary keys cover subsequent lookups. Transactional builds scan retained rows
and block writes: assess lock windows, disk/index/WAL growth and deployment load.
Representative upgrade and empty-chain tests exercise the appended migration.
Rollback removes the read adapter/route allowlist while retaining harmless indexes
and immutable deliveries. No backfill, source command or DTO changes elsewhere.

## Security and operations

Tenant and recipient predicates remain in each SQL branch. Actor and provider
identities, question/answer/note text, claims and evidence never enter the feed.
Bounded output is not a guarantee of constant query cost; indexes support the
recipient joins without caching authorization. Existing private database roles,
retention and backup policies apply. There is no external delivery or public
production-safe fallback. A requester deployment still cannot disable its session
boundary without stopping, per ADR 0068.

## Validation

Run policy, formatting, static analysis, compilation and focused non-container
tests locally. CI owns PostgreSQL/HTTP recipient, client, audience, tenant, empty,
bounded/deterministic, privacy and lost-target access paths plus migrations.
Unit tests withdraw results if authority expires during retrieval and preserve
storage failures; disabled deployment returns 503. No browser/UI/guide execution
or live OIDC provider acceptance is claimed by this backend slice.
