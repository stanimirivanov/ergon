# ADR 0069: Disclose requester-owned case progress

- Status: Accepted
- Date: 2026-10-25
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M05-PR16 needs case progress behind the separate requester browser boundary.
Legacy cases have no authenticated requester attribution. Approval attestations
authorize a different capability and must not silently become ownership.

## Decision

Record one immutable initial requester in `case_requesters`, with tenant-safe
case/actor foreign keys, database recording time and a bounded trusted provenance
reference. Do not backfill or infer ownership from observations, actor IDs in
legacy requests, resolver assignment or REQUESTER approval evidence.

Expose only GET `/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/progress`
in requester mode, requiring its OIDC client and a registered tenant actor.
One SQL statement joins the explicit ownership and public case projection under
the exact tenant, actor and case. Return only `caseId`, `goal`, `status`,
`openedAt` and `updatedAt`. Absence and non-ownership share the same 404 problem;
an unregistered tenant identity retains the session contract's 403.

OPEN is only a case lifecycle state, not a runtime, approval or human-work state.
VERIFIED_RESOLVED is the existing case projection established by accepted proof;
this read cannot finalize proof or close cases. Never load source timelines,
notes, authority, contracts, runs or proof payloads into this read path.

Until authenticated intake (M05-PR23), a trusted database operator may bootstrap a
reviewed binding. This is an explicit provisioning limit, not a public writer or
temporary API stub. The table is consumed immediately by this read; later intake
will bind the authenticated creator atomically with case creation. No browser
ownership-assignment endpoint or legacy writer change is introduced now.

## Alternatives considered

Reusing approval evidence conflates permission with ownership and would force
intake to grant approval authority merely to enable progress. Matching observation
text or subjects invents attribution. Automatically exposing all tenant cases
breaks isolation. A new administrative machine API expands this read-only slice;
controlled privileged provisioning is explicit until the planned intake producer.

## Consequences

Legacy/unattributed cases remain hidden. Each binding grants only this bounded
read, not approval, execution or resolver authority. No requester discovery list,
delegation, reassignment or revocation protocol is implied. The first requester
UI therefore starts from an explicitly supplied case link, not guessed discovery.
An incorrect binding requires disabling requester access and reviewed forward
repair; never silently overwrite ownership or weaken the mutation guards.

## Compatibility and migration

Deploy V20261025051735 before the new requester reader. Its empty table/primary
index/triggers require no old-row rewrite or backfill; FK creation briefly locks
referenced tables, so schedule a DDL window. Existing writers coexist and continue
creating unowned cases. The primary key serves the exact-case read; no speculative
discovery index is added. Future bindings generate normal row/index WAL and retain
audit. Rollback stops the requester deployment, retaining schema and bindings.

## Security and operations

Preserve ADR 0068's separate origins, clients, cookies and fail-closed startup.
Session identity is server-resolved; query parameters cannot select the actor.
Responses are no-store. Database privileges, not the browser session, govern
bootstrap writes; application predicates are not RLS for direct SQL access.
Provisioning must verify case/actor identity and retain a review reference.
The goal is untrusted text for future UI rendering, never executable markup.

## Validation

PostgreSQL-backed HTTP tests cover own/no-approval access, client/session/tenant
denials, identical hidden-case problems and exact response fields. Integrity tests
reject cross-tenant keys, duplicate owners, invalid provenance and mutation.
An independent released-schema upgrade preserves existing cases as unowned;
the application starts from a complete empty-chain migration. Existing workbench
and disabled-mode regressions plus the complete reactor remain required. Live
IdP/two-origin acceptance and requester UI/guide belong to their later slices.
