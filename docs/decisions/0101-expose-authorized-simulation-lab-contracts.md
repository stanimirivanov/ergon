# ADR 0101: Expose authorized Simulation Lab contracts

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

PR10/11 supply immutable qualification, promotion and optional runtime enforcement,
but their trusted internal routes are not browser authorization boundaries. The
Simulation Lab needs discovery, safe details and a reviewed confirmation flow.
The approved plan also requires withdrawal of current access, beyond expiry.

## Decision

Use the explicitly approved dedicated tenant-wide EVALUATION_VIEWER entitlement.
Trusted internal issuers attest a registered actor, provenance and explicit expiry
within 24 hours, with server-stamped microsecond time. Neither RESOLVER nor
CONTRACT_PROMOTER inherits viewer permission. Discovery, report detail and promotion
review require current viewer evidence; confirmation additionally requires current
CONTRACT_PROMOTER evidence through PR10.

Use the existing workbench OIDC session, registered tenant actor resolution and
BFF CSRF filter. A path tenant is only a requested scope: the principal must resolve
to a registered actor in that tenant. Actor IDs and authority claims in browser
commands are rejected; actor headers cannot replace the resolved session identity.
Requester-audience allowlists do not expose these routes.

Expose bounded newest-first report discovery with a tenant-checked report UUID
cursor and page size 1–50. Details project report/artifact identities, digest,
recording time, derived pass flag, typed assertion outcomes and budgets. Omit raw
fixtures, observed values, report definitions, submitting actors and source
provenance. Pagination is keyset traversal, not a durable snapshot or total count.
No fixture upload, authoring or evaluation execution endpoint is added.

Review derives the current binding and its exact suite/contract/policy on the
server; callers explicitly select report IDs/digests. Return the review intent and
eligibility. Confirmation submits that exact versioned intent and a command UUID,
reusing PR10's eligibility and retry checks. Review is an advisory snapshot, not a
durable permit or lock. Safe receipts omit authority proof and rationale.

The maintainer approved append-only revocation of both viewer and promoter
attestations. Revocation names an exact evidence ID with issuer provenance/reason;
exact retries retain original time and changed intent conflicts. Revocation is
per evidence, not per actor: another current non-revoked grant still qualifies.
Reattesting the same ID cannot renew or resurrect it; renewal needs a new ID.

Serialize revocation and promotion authority use with a tenant/actor transaction
lock. Promotion order is command, contract, actor. Revocation takes only actor.
Promoter selection and SQL admission check revocation after locking, including exact
command recovery. Browser confirmation checks viewer access initially and again
under the retained actor lock before commit. If revocation won during a contract
wait, the final check rolls back the new promotion and all evidence links. Reads
use current snapshots; they do not promise continuous authorization after response.

## Alternatives considered

### Reuse resolver or promoter permission for viewing

- Benefits: Fewer grant records and simpler provisioning.
- Costs and risks: Implicitly expands access to evaluation results.
- Reason not selected: The maintainer selected separate EVALUATION_VIEWER authority.

### Rely only on expiry

- Benefits: Keeps the PR10 authority lifecycle unchanged.
- Costs and risks: Access cannot be withdrawn before expiry; misses approved revocation coverage.
- Reason not selected: The maintainer explicitly approved revocation for both kinds.

## Consequences

### Positive

Browser identity, tenant, CSRF and current access are explicit. Existing immutable
report and promotion semantics remain the source of truth. Revocation races are
ordered without deleting audit history or introducing new dependencies.

### Negative

Operators provision two independent grants for promotion users. Confirmation can
fail after a successful review. The discovery index scans the existing report
ledger and blocks concurrent report writes during transactional index creation.

### Neutral or follow-up

Revocation prevents future authority use, including exact promotion-command retries.
It does not revoke historical promotions, admitted runs or PR11 qualification.
Frontend components remain PR13/14. Internal issuer routes require a protected
management boundary; their provenance strings are not credential verification.

## Compatibility and migration

Apply additive V20261030195306 before new readers/writers. It adds viewer grants,
viewer/promoter revocations, the shared actor lock and the report discovery index;
replaces the promoter admission function to check revocation; and does not rewrite
historical evidence. Existing exact promotion retries now require non-revoked
current authority. Source fixtures and prior migration checksums are unchanged.

Schedule index creation for report-ledger size, disk/WAL headroom and acceptable
writer blocking. No backfill is needed. Disable browser/issuer traffic during mixed
version deployment: old promoter retry readers do not understand revocation.
After revocation is used, roll forward or retain compatible revocation-aware readers;
rolling back to PR10/11 app code could acknowledge revoked exact retries. Preserve
new tables/functions and all immutable history; never remove revocations to recover.

## Security and operations

Versioned command bodies are strict UTF-8 JSON up to 128 KiB. Mutation CSRF and
session checks precede controller execution. Application projections are explicit;
raw definitions and identity provenance are not serialized accidentally. Responses
and application errors use no-store. Missing permission and private resource absence
share 404; stale/ineligible commands use 409; malformed requests 422; oversize 413;
unexpected storage failures 503. Standard filters own unauthenticated/CSRF failures.

## Validation

Use real BFF filters for absent/retired sessions, tenant mismatch, missing CSRF,
current permission checks, strict body rejection, stale review, safe payload and
exact retry tests. Domain tests cover bounded expiry/provenance. PostgreSQL tests
cover released-schema upgrade, immutable revocation/retry, expiry, direct admission
refusal, viewer-revocation rollback, actor-lock ordering and keyset ties/isolation.
Report Docker skips as unverified; run migration and concurrency checks in CI.
