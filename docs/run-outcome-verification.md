# Supervisor-scoped outcome verification

## TL;DR

The Console reads one pinned outcome condition. A current MATCHING assessment is
not durable ACCEPTED proof. Only the exact selected post-action binding/source is
disclosed under current supervision and resolver authority. Reads never accept
proof, execute actions or close cases.

## Browser contract

`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/console` adds
`outcomeVerification`: `status`, `property`, `expectedValue`, `caseStreamVersion`,
`reason`, `evidence` and `acceptance`. The property is currently
`account.access.state`. All nullable fields are explicitly present.
`expectedValue` preserves the contract's exact non-blank string (maximum 200
characters), not an account-state enum or normalized label. An unsupported
expected value can remain pending; it must not invalidate an otherwise valid run.

| Status | Meaning | Evidence / acceptance |
|:--|:--|:--|
| NOT_ASSESSED | Run is not eligible; reason RUN_NOT_VERIFYING | Both null; assessed version null |
| PENDING | ELIGIBLE_EVIDENCE_MISSING at the returned case version | Both null |
| PENDING | VALUE_MISMATCH for the latest eligible binding | Exact mismatching evidence; acceptance null |
| MATCHING | Current evaluator match, not committed | Exact matching evidence; acceptance null |
| ACCEPTED | Immutable runtime decision and paired case closure | Frozen evidence and acceptance |

Evidence contains fact identity/stream position, value, account reference,
binding time and exact source: observation identity/position, CONNECTOR origin,
provider, reference, content and observation time. Source and binding positions
both follow the run pin; source occurrence cannot precede action completion.
The source precedes its binding and its reference matches the account. Only the
selected citation crosses the browser boundary; there is no later-history list.

Acceptance contains run sequence, accepted and database-recorded instants, and
case closure version. It agrees with the terminal run revision/update; closure
immediately follows the assessed case version. Internal event and authority
identities stay private. Case-event recording times are not supplied by the
event-history port and are not inferred from occurrence or acceptance time.

## Snapshot semantics and authorization

Current tenant-wide RESOLVER evidence and exact current supervision gate all
private reads, including terminal detail. Missing/denied access is neutral.
Pinned evidence keeps its original prefix; outcome verification separately
permits narrowly selected post-action evidence. No unrelated source, current
transcript, case goal or browser-selected evidence scope is added.

VERIFYING uses one loaded case history and the existing evaluator. A later
eligible value can replace a matching assessment. VERIFIED_RESOLVED reads the
stored acceptance and exact linked case closure, never a fresh terminal
assessment. Inconsistent durable attribution fails closed. A concurrent acceptance
does not enter an earlier observed state prefix; explicit recheck obtains a new
snapshot. These are not continuous monitoring or revocation guarantees.

## Confirm exact reviewed proof

`POST /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/proof-finalizations`
requires an authenticated session and CSRF. Its body is `commandId`,
`expectedSupervisionRevision`, `expectedRunVersion`, `expectedCaseVersion`,
`factId` and `observationId`. Actor, resolver attestation, contract and time remain
server-owned. First application requires current supervision, tenant-wide
RESOLVER authority and VERIFYING at the reviewed run revision. Reassessment must
still match the exact case version and both identities. Changed evidence, even
another matching fact, requires a new review and returns conflict rather than
substituting proof. Concurrent case appends roll back the operation.

First acceptance returns 201; exact replay returns 200 with identical persisted
timestamps. Both acknowledge `commandId`, `runId`, `supervisionRevision`,
`expectedRunVersion`, `assessedCaseVersion`, `factId`, `observationId`,
`runSequence`, `caseClosureStreamVersion`, `acceptedAt`, `recordedAt`. Acceptance,
run completion, case closure and attributed receipt commit atomically. Replay
requires the original actor and current resolver authority, not continued
supervision; it never closes twice or adopts an existing internal acceptance.
The receipt is historical, not present authority or permission to disclose proof.

Stable no-store problems are `run-proof-finalization-conflict` (409),
`run-proof-finalization-disabled` (503), `invalid-run-proof-finalization` (400),
and the existing neutral `assigned-resolution-run-not-found` (404). Existing
identity, session and CSRF problems remain unchanged. The client never retries
automatically: uncertain results may be replayed only with unchanged intent.

## Deployment and limits

The read projection needs no new dependency. Deploy backend before UI. Omitted
projection on an older server means unsupported; explicit null or malformed
proof rejects the entire UI resource. Application rollback removes disclosure
without altering evidence or acceptance.

Browser finalization additionally needs V20261015120000 and
`ERGON_RUN_PROOF_FINALIZATION_ENABLED=true` (default false). Deploy schema/code
before enabling. The migration scans/locks acceptance rows for an exact evidence
unique key; assess size, disk, WAL and lock time. No backfill. Disable the flag
for application rollback; retain all receipts and durable closures. The additive
`proofFinalizationSupported` Console field distinguishes unsupported omission from
disabled false; enabled true does not itself establish matching proof or authority.
Existing internal acceptance semantics remain unchanged. See
[ADR 0052](decisions/0052-finalize-reviewed-proof-through-browser.md).

Only one existing proof condition is supported: no synthetic check count,
confidence, generalized contradiction, retry or compensation is inferred.
Assessment still replays complete case history internally, as the internal
endpoint does; selected output does not bound replay cost. See
[ADR 0051](decisions/0051-disclose-supervisor-scoped-outcome-verification.md).
