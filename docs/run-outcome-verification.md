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

## Deployment and limits

No migration, backfill, flag or new dependency. Deploy backend before UI. Omitted
projection on an older server means unsupported; explicit null or malformed
proof rejects the entire UI resource. Application rollback removes disclosure
without altering evidence or acceptance. The browser has no acceptance command.

Only one existing proof condition is supported: no synthetic check count,
confidence, generalized contradiction, retry or compensation is inferred.
Assessment still replays complete case history internally, as the internal
endpoint does; selected output does not bound replay cost. See
[ADR 0051](decisions/0051-disclose-supervisor-scoped-outcome-verification.md).
