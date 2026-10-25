# Requester information requests

## TL;DR

M05-PR19a supplies validated question values and immutable tenant-scoped storage.
There is no HTTP command or new read surface yet. PR19b adds authenticated
creation/recovery; PR19c adds protected reads, before PR20 UI and user guide.

## Storage contract

Each tenant-wide request UUID records one exact question, source work/case and
historical ownership revision. Author/claim are reconstructed from the immutable
CLAIMED event; the recipient is the case's immutable explicit requester binding.
Foreign keys and a source-case trigger reject contradictory relationships.

The repository requires a READ COMMITTED transaction. Both first write and retry
return the database winner, including database timestamp precision and derived
attribution. A competing writer cannot merge text or timestamps into that winner.
The future application command must compare intent and check current access
before disclosing it: storage alone does not authorize requests or recovery.

Text preserves whitespace and accepts at most 4000 Unicode code points, rejecting
blank text, NUL and unpaired surrogates. A question is untrusted plain text, never
evidence, instructions, consent or approval. Recording has no delivery meaning.

## Rollout and limits

Apply V20261025141955 before future producers/readers. No backfill or existing-row
rewrite is needed; foreign-key DDL briefly locks parent tables. Keep additive
schema and immutable audit on rollback. No edit/delete/retention API exists.

Current browser contracts are unchanged. No case/run/ownership revision changes,
notifications, responses or user-guide update are included in this backend
foundation. See [ADR 0070](decisions/0070-record-requester-information-requests.md).
