# Requester information requests

## TL;DR

M05-PR19c adds protected owner history and requester-safe questions over PR19a/b
storage and commands. Recording and reading are not delivery or evidence.
M05-PR21b adds authenticated requester answers and exact recovery over PR21a storage.
M05-PR21c adds narrow answers to both protected histories; PR22 UI and user guide follow backend review/merge.

## Answer command and recovery

POST `/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/information-requests/{requestId}/responses`
from the requester deployment with its session CSRF. Input is
`{"responseId":"<uuid>","answer":" exact plain text "}`, never actor or timestamps.
Only the verified explicit requester can answer an OPEN case/question; resolver
claims and authority are irrelevant. Lock the case through insertion/recovery.
Closure after a lock wait withdraws both creation and replay.

201 creates; 200 acknowledges exact response UUID, question and text under fresh
access. Retain original intent after uncertainty; never automatically replace
its UUID. Receipt fields are `responseId`, `requestId`, `caseId`, `answer`,
`respondedAt` and `recordedAt`, without actor/provider or resolver context.
No source stream, binding, runtime, ownership or proof revision advances.

Problems use `urn:ergon:problem:`: `404 requester-response-not-found` for
unavailable/foreign/unbound/closed/wrong-question targets, including replay;
`409 requester-response-conflict` for another answer or changed intent without
winner disclosure; `400 invalid-requester-response` for invalid text. Existing
session/CSRF/identity failures and no-store remain. Workbench sessions are denied;
disabled browser authentication returns 503. See [ADR 0074](decisions/0074-authorize-requester-answer-commands.md).

Deploy V20261026102606 before the command. No new migration, dependency or flag.
Block this POST at ingress or roll back application code for behavioral rollback;
preserve additive schema and immutable audit. UI/guide remain a separate dependent slice.

## Command and recovery

POST `/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/information-requests`
with workbench session CSRF and
`{"requestId":"<uuid>","claimId":"<uuid>","expectedOwnershipRevision":1,"question":"Which workspace did you open?"}`.
The session supplies the author and the case's immutable binding the recipient.
Work must be active and currently owned under resolver authority; the bound case
must be OPEN. Authority is rechecked after case/identity waits before acknowledgement.

First write returns 201; exact authorized replay returns the original 200 receipt.
Reuse only the original UUID, question, claim and revision after uncertainty.
Receipt fields are `requestId`, `caseId`, `claimId`, `ownershipRevision`,
`authorActorId`, `question`, `requestedAt`, `recordedAt`. Timestamps are server
instants, not delivery or response times. No revisions advance.

Problems use the `urn:ergon:problem:` prefix:
`404 requester-information-request-not-found` for every inaccessible target,
`409 requester-information-request-conflict` for stale owned coordinates or
changed intent, and `400 invalid-requester-information-request` for invalid
question/revision. Replay requires fresh access, even if a receipt was retained.
Browser-disabled requests keep the existing 503; session/CSRF/no-store rules apply.

## Protected history

GET the same work resource as the current authorized resolver owner. Active work
and an OPEN explicitly bound case are mandatory, including for empty history.
Work-then-case locks serialize access with ownership and case changes; authority
is checked again after waits and selection. Items retain all question POST receipt fields,
including immutable predecessor attribution, plus nullable `response`; release/completion/expiry withdraw
access with the existing neutral information-request 404.

In the separate requester deployment, GET
`/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/information-requests`.
Only explicit tenant/case ownership grants access. Items contain exactly
`requestId`, `question`, `requestedAt`, `recordedAt`, `response`; no author, work, claim,
recipient/provider identity, evidence or run data is loaded into this projection.
An owned empty case returns an empty page; absent, unbound, foreign-owned and
cross-tenant cases share `404 urn:ergon:problem:requester-case-not-found`.
Closure and resolver ownership changes do not erase requester history.

In either history, `response` is null when no answer exists in that SQL snapshot;
otherwise it contains exactly `responseId`, `answer`, `respondedAt`, `recordedAt`.
Text and timestamps are the whole persisted winner, not a merged command or a
delivery/evidence/consent/verification assessment. No recipient/provider identity
is loaded. Answers join on tenant and question, using the existing unique index;
answer time/presence never changes the question order or truncation boundary.
Reads do not acknowledge delivery or mutate case, work, run or proof state.

Both no-store pages have `items` and `truncated`, ordered newest-first by
`(recordedAt, requestId)`, at most 100 items. Truncation means older questions were
omitted, never answered/delivered. Requester GET is explicitly allowlisted;
workbench sessions cannot use it, and requester sessions cannot use resolver
reads or writes. Browser-disabled reads return the established 503. See
[ADR 0072](decisions/0072-protect-requester-information-history.md).
The additive answer disclosure is governed by
[ADR 0075](decisions/0075-disclose-protected-requester-answers.md).
Question/answer POST receipts remain unchanged. Old readers may ignore `response`;
mixed-version clients must tolerate its absence until all readers are upgraded.
No migration, dependency or flag is added; deploy the existing response schema
V20261026102606 before these readers, then the dependent UI. Application rollback
removes answer disclosure without deleting any immutable history.

## Storage contract

### Response foundation

M05-PR21a adds immutable answer storage, not a browser command or disclosure.
One final answer is allowed per tenant/question; a response UUID identifies
tenant-wide exact intent. Text preserves whitespace and uses the question's
4000-code-point/Unicode constraints. Case/requester attribution is reconstructed
from immutable parents. Answers remain untrusted source material, never facts,
consent, approval or proof; no case observation stream is appended by this foundation.

The response repository requires a caller-owned READ COMMITTED transaction.
After either unique-key wait it returns one whole persisted winner; response-ID
collision takes precedence when the supplied question identifies another winner.
The caller must compare every intent coordinate and authorize before disclosure.
No current-access or OPEN-case check is supplied by storage.

PR21b adds authenticated recording/recovery; PR21c adds protected reads before
PR22's UI and user guide. Deploy V20261026102606 first. This empty-table additive
migration briefly locks the question parent for FK DDL, without scanning or
rewriting old rows. Preserve immutable schema/audit on rollback and budget future
answer retention, WAL and backups. See [ADR 0073](decisions/0073-record-immutable-requester-answers.md).

### Question storage

Each tenant-wide request UUID records one exact question, source work/case and
historical ownership revision. Author/claim are reconstructed from the immutable
CLAIMED event; the recipient is the case's immutable explicit requester binding.
Foreign keys and a source-case trigger reject contradictory relationships.

The repository requires a READ COMMITTED transaction. Both first write and retry
return the database winner, including database timestamp precision and derived
attribution. A competing writer cannot merge text or timestamps into that winner.
The application command compares intent and checks current access before
disclosing it: storage alone does not authorize requests or recovery.

Text preserves whitespace and accepts at most 4000 Unicode code points, rejecting
blank text, NUL and unpaired surrogates. A question is untrusted plain text, never
evidence, instructions, consent or approval. Recording has no delivery meaning.

## Rollout and limits

Apply V20261025141955 before the command. V20261025185000 adds tenant/work and
tenant/case history indexes, scanning retained questions and blocking writes
during their transactional builds; budget a migration window, I/O, index/WAL
space and replication lag. Preserve these additive indexes on application rollback.
No backfill or existing-row
rewrite is needed; foreign-key DDL briefly locks parent tables. Keep additive
schema and immutable audit on rollback; block this POST at ingress or stop its
deployment to disable writes. No edit/delete/retention API exists.

Existing write contracts are unchanged; the histories gain only nullable `response`.
No notifications, observation ingestion or UI/guide update are included in this backend slice. See
[ADR 0070](decisions/0070-record-requester-information-requests.md) and
[ADR 0071](decisions/0071-authorize-requester-information-commands.md).
