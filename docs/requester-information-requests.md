# Requester information requests

## TL;DR

M05-PR19b adds authenticated current-owner creation and exact recovery over PR19a
storage. Recording is not delivery or evidence. Protected reads follow in PR19c,
before PR20 UI and user guide.

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

## Storage contract

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

Apply V20261025141955 before this command or future readers. No backfill or existing-row
rewrite is needed; foreign-key DDL briefly locks parent tables. Keep additive
schema and immutable audit on rollback; block this POST at ingress or stop its
deployment to disable writes. No edit/delete/retention API exists.

Existing browser contracts are unchanged. No notifications, responses or UI/guide
update are included in this backend command. See
[ADR 0070](decisions/0070-record-requester-information-requests.md) and
[ADR 0071](decisions/0071-authorize-requester-information-commands.md).
