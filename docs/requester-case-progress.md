# Requester case progress

## TL;DR

In the separate requester deployment, read one explicitly owned case using
`GET /bff/v1/tenants/{tenantId}/requester-cases/{caseId}/progress`. A registered
tenant identity alone is insufficient. Legacy cases remain hidden until a trusted
binding exists; approval evidence never supplies that binding.

## Contract and privacy

The no-store JSON object contains exactly `caseId`, `goal`, `status`, `openedAt`
and `updatedAt`. Status is the existing OPEN or VERIFIED_RESOLVED case lifecycle,
not run progress, a claim, approval readiness or human-work completion. Timestamps
describe case changes, not verification or requester-binding recording times.
Goal text is untrusted; consumers must render it as text.

No source observations, connector references, internal notes, owner IDs, contract,
run, approval or proof payload is returned. Reads do not mutate or acquire anything.
There is no collection/list endpoint: callers need an explicitly supplied case
link. [Question history](requester-information-requests.md#protected-history) is a
separate narrow read, not part of progress. [Intake](requester-case-intake.md) and
[answers](requester-information-requests.md#answer-command-and-recovery) are
separate authenticated commands; discovery remains deferred.

Missing, unbound, foreign-owned and cross-tenant cases return the same
`404 urn:ergon:problem:requester-case-not-found` type/title/detail. Standard
`instance` reflects only the caller's URL. Missing sessions retain the browser
401/sign-in contract; an unregistered tenant identity is 403. Other-client sessions
are rejected. Workbench mode denies this route; default browser-disabled mode
returns the established authentication-unavailable 503.

## Trusted bootstrap and rollout

Deploy V20261025051735 before this reader. The additive empty table does not
backfill/rewrite old rows or scan existing data. Its primary index is empty;
foreign-key DDL briefly locks referenced tables. Budget retained binding rows,
index/WAL growth and a migration window. Old case writers remain compatible and
produce unowned cases. Keep schema/bindings on rollback and stop the requester
deployment as described in [authentication](authentication.md#requester-browser-deployment).

[Authenticated intake](requester-case-intake.md) now binds new cases atomically.
For legacy cases, only a trusted database operator can provision a reviewed binding
for a browser caller; intake never adopts or reassigns those cases.
No HTTP input, observation or approval attestation proves ownership. Verify the
exact tenant, case and registered actor out of band, then execute this parameterized
statement through the approved database tooling (placeholders are bound values):

```sql
INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
VALUES (:tenant_id, :case_id, :verified_actor_id, :review_reference);
```

`source_reference` is a nonblank audit reference of at most 500 characters, not
customer content or credentials. Inspect an existing binding before handling a
duplicate; never hide a different owner with blind conflict handling. Updates,
deletes and truncation are rejected. No reassignment/revocation workflow exists;
on incorrect provisioning, stop requester access and use reviewed forward repair.
Binding and source references remain private durable audit data, with no cascade
deletion. Runtime SQL accounts must not be exposed to browser users; this is not
an RLS design or a public provisioning contract.

The [PR23a intake foundation](requester-case-intake.md) creates the case and binds
its verified creator in one transaction, without minting approval authority.
Its authenticated browser producer is PR23b. UI/guide
implementation follows backend review/merge; live IdP/two-origin acceptance remains
unverified. See [ADR 0069](decisions/0069-disclose-requester-owned-case-progress.md).
