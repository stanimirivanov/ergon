# Architecture decision records

## TL;DR

- Use an ADR for durable choices affecting compatibility, security, persisted
  meaning, ownership, topology, foundational technology, or multiple modules.
- Number ADRs sequentially and never reuse a published number.
- Allocate from current repository state and renumber a concurrent collision.
- Accepted ADRs are history; supersede rather than rewrite them.
- Record alternatives, consequences, compatibility, security/operations, and
  validation—not only the selected technology.

## When an ADR is required

Create an ADR when a decision materially affects one or more of:

- public contracts or compatibility;
- persistence, durability, data meaning, ownership, or migration strategy;
- security, privacy, authorization, or trust boundaries;
- service, repository, or deployment topology;
- language, framework, database, queue, model provider, or build foundation;
- cross-component operational behavior; or
- a constraint future contributors might otherwise simplify away.

Local choices that are cheap to reverse do not need an ADR. Ordinary ambiguity
follows [the contributor escalation rules](../../CONTRIBUTING.md#ambiguity-and-escalation).

## Naming and lifecycle

Use a four-digit sequence and short kebab-case name:

```text
0029-create-durable-human-follow-up-work-items.md
```

Statuses are `Proposed`, `Accepted`, `Rejected`, `Deprecated`, or `Superseded`.
A superseded ADR links to its replacement. Do not edit the decision and
consequences of an accepted ADR to make history appear cleaner; add a note or a
new ADR.

Before creating one, refresh the branch as the workflow permits and inspect
this directory. Allocate the lowest unused number. If concurrent work collides,
rename the later unshared ADR and update its title and references. Published
numbers remain immutable.

ADRs 0001–0005 describe the predecessor RAG Help Center and are superseded by
the Ergon rewrite boundary. They remain useful history but are not authority for
new Ergon behavior. Ergon decisions begin at 0006.

## Template

Copy [`0000-template.md`](0000-template.md). Keep the record concise enough to
review with the implementing change; link schemas, contracts, threat models, or
benchmarks rather than duplicating them.

## Index

| ADR | Decision |
|:--|:--|
| 0001 | [Event time and validation ownership](0001-event-time-and-validation-ownership.md) |
| 0002 | [Synchronous projections and transactional outbox](0002-synchronous-projections-and-transactional-outbox.md) |
| 0003 | [Publication events carry revision snapshots](0003-publication-integration-event-carries-revision-snapshot.md) |
| 0004 | [Embedding-worker projection and idempotency](0004-embedding-worker-projection-and-idempotency.md) |
| 0005 | [Hybrid retrieval ranking](0005-hybrid-retrieval-ranking.md) |
| 0006 | [Adopt the Ergon product and rewrite boundary](0006-adopt-ergon-product-and-rewrite-boundary.md) |
| 0007 | [Bind account state to source observations](0007-bind-account-state-to-source-observations.md) |
| 0008 | [Use strict YAML for resolution contracts](0008-use-strict-yaml-for-resolution-contracts.md) |
| 0009 | [Store normalized contract revision snapshots](0009-store-normalized-contract-revision-snapshots.md) |
| 0010 | [Pin one contract revision per case](0010-pin-one-contract-revision-per-case.md) |
| 0011 | [Register contract semantic references](0011-register-contract-semantic-references.md) |
| 0012 | [Evaluate resolution readiness from case history](0012-evaluate-resolution-readiness-from-case-history.md) |
| 0013 | [Apply versioned deny-by-default step policy](0013-apply-versioned-deny-by-default-step-policy.md) |
| 0014 | [Store immutable resolution-run starts](0014-store-immutable-resolution-run-starts.md) |
| 0015 | [Store expiring approval requests](0015-store-expiring-approval-requests.md) |
| 0016 | [Model scoped human authority evidence](0016-model-scoped-human-authority-evidence.md) |
| 0017 | [Authenticate human actors from JWT subjects](0017-authenticate-human-actors-from-jwt-subjects.md) |
| 0018 | [Record authenticated approval decisions](0018-record-authenticated-approval-decisions.md) |
| 0019 | [Derive bounded capability authorization grants](0019-derive-bounded-capability-authorization-grants.md) |
| 0020 | [Consume authorization through tenant routes](0020-consume-authorization-through-tenant-capability-routes.md) |
| 0021 | [Invoke consumptions with stable idempotency](0021-invoke-consumptions-with-stable-idempotency.md) |
| 0022 | [Project receipts into resolution-run state](0022-project-receipts-into-resolution-run-state.md) |
| 0023 | [Require post-action evidence for outcome proof](0023-require-post-action-evidence-for-outcome-proof.md) |
| 0024 | [Atomically accept proof and close the case](0024-atomically-accept-proof-and-close-the-case.md) |
| 0025 | [Model retries as linked resolution runs](0025-model-retries-as-linked-runs.md) |
| 0026 | [Require resolver authority for retry](0026-require-resolver-authority-for-retry.md) |
| 0027 | [Bound explicit retry attempts](0027-bound-explicit-retry-attempts.md) |
| 0028 | [Record explicit exhausted-run escalation](0028-record-explicit-exhausted-run-escalation.md) |
| 0029 | [Open durable work from escalation](0029-open-durable-work-from-escalation.md) |
| 0030 | [Expose an oldest-first resolver inbox](0030-expose-oldest-first-resolver-inbox.md) |
| 0031 | [Claim human follow-up work immutably](0031-claim-human-follow-up-work.md) |
