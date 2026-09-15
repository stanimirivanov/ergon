# Ergon Delivery

> **TL;DR:** Prove the product with one complete access-restoration case before
> adding channels, providers, ingestion formats, or infrastructure. Every step
> is a reviewable pull request that leaves the repository working.

## First vertical slice

Restore access to a fictional SaaS workspace:

1. A requester reports that sign-in fails through the web canvas or API.
2. Ergon opens a case and identifies missing evidence.
3. A fake identity connector observes account state and entitlements.
4. The runtime selects a pinned access-restoration contract.
5. Policy determines whether unlock, reset, or identity review is allowed.
6. The requester or resolver approves an action when policy requires it.
7. The connector executes once using an idempotency key.
8. A separate observation verifies that a login challenge succeeds.
9. Only accepted proof moves the case to `VERIFIED_RESOLVED`.
10. A novel or failed path produces an improvement proposal and replay fixture.

The slice runs locally without paid services. CI uses deterministic model and
connector adapters.

## Rewrite sequence

### 0. Documentation baseline

Establish the product boundary, architecture rules, contribution standards, ADR
policy, and delivery model. Keep predecessor code executable while clearly
marking it for replacement.

### 1. Case kernel

Implement case identity, goal, observations, facts, lifecycle events, optimistic
concurrency, and a timeline projection. Accept one API request and one fake
connector observation. Do not add AI.

The first two sub-slices cover identity, goal, source observations, append
concurrency, the timeline, and a typed account-access state bound to connector
evidence. AI-assisted binding, additional fact types, and further lifecycle
events remain separate PR-sized capabilities.

### 2. Contract kernel

Define the smallest useful textual contract schema. Validate applicability,
required evidence, steps, risk, approvals, and outcome proof. Pin a contract
revision to a case. Do not build a visual editor.

The first sub-slices define strict YAML validation, persist immutable,
tenant-scoped normalized revisions, and let a case pin one exact published
revision. Publication now checks fact and capability names against the built-in
system vocabulary. Automatic selection, tenant availability, replanning, and
execution remain separate PR-sized behavior.

### 3. Resolution runtime

Execute deterministic steps with durable state, idempotency, retries, waits,
receipts, and verification. Complete the access-restoration happy path and
failure path with fake tools.

The first sub-slice evaluates whether the exact pinned revision has all required
typed evidence and whether its applicability equality holds at one case stream
version. Durable runs, policy snapshots, approvals, capability invocation,
receipts, retries, and outcome verification remain separate behavior.

The policy sub-slice applies the immutable access-restoration policy to the first
step of a ready contract. The run-start sub-slice then persists the exact case,
contract, policy, step, risk, and approval snapshot under an optimistic case-
version precondition. It begins in `WAITING_FOR_APPROVAL` but grants no authority
and performs no action. Append-only run transitions, approval records,
capability invocation, receipts, retries, and proof remain separate behavior.

### 4. Policy and human authority

Add capability scopes, denial reasons, approval requests, expiry, and resolver
intervention. Prove that model output, retrieved text, and channel callers cannot
grant authority.

The initial policy evaluator is deny-by-default, versioned, and can only
strengthen contract declarations. The approval-request sub-slice appends
immutable 15-minute requests, derives expiry from time, and permits replacement
only after expiry. Human identity bindings and authority attestations now make
requester authority case-scoped and resolver authority tenant-scoped. Signed,
issuer-validated JWT identity can now resolve the current actor inside one
tenant. Authenticated actors can now record one immutable approval or rejection
only while both the request and correctly scoped authority evidence are current.
A current approved decision can now derive one immutable grant for the exact
run case, policy revision, step, and capability until the request expires. The
grant can now be consumed once only through a connector route configured
for the same tenant and capability. The consumption is a durable reservation,
not an execution receipt. That reservation can now invoke the deterministic
identity connector outside the database transaction and persist or replay its
terminal receipt using the consumption identity as the provider idempotency
key. The receipt-backed run-state projection now appends an immutable event and
advances the current state to
`VERIFYING` or `ACTION_FAILED`; successful execution leaves the case open. The
outcome-proof assessment now requires a separate post-action observation and
fact binding, evaluates the pinned equality at an exact case version, and leaves
run and case state unchanged. A separate acceptance command now freezes that
exact evidence as run event sequence two and atomically advances both the run
and case to `VERIFIED_RESOLVED`. Failed runs can now be explicitly superseded by
one durable successor that retains operation meaning while recalculating
evidence and safeguards. Each attempt requires new approval and authorization
and receives a new provider idempotency key. Retry now requires a verified human
identity with current tenant-wide resolver evidence, and the immutable event
retains that actor and attestation. Retry eligibility now has a versioned ceiling
of two total attempts, frozen on each new retry event. A resolver can now record
explicit escalation only after that budget is exhausted; the immutable event
freezes the actor, authority evidence, and denial inputs while leaving the case
open. The next sub-slice should model a durable human follow-up work item before
adding assignment or notification delivery.

### 5. Adaptive canvas and resolver console

Ship the minimum UI for request intake, evidence collection, approval, timeline,
verification, and structured handoff. The UI consumes the same case API; it does
not own workflow state.

### 6. Evidence compiler

Ingest one document format and one API schema. Separate protocol observations
from semantic binding, preserve source spans, extract scoped claims and
procedures, and surface contradictions for review.

### 7. Simulation and promotion

Replay pinned runs against recorded tool fixtures and case scenarios. Gate a
contract revision on deterministic assertions, safety policy, outcome success,
cost, and latency budgets.

### 8. Improvement loop

Compare completed runs with their contracts and sources. Produce reviewable
documentation, contract, contradiction, and regression-test proposals; never
modify production behavior automatically.

### 9. Ecosystem

Publish connector, contract, and widget SDKs. Integrate with an existing help
desk before building a full inbox. Add MCP as a guarded client surface over the
same case and capability APIs.

## Extraction from predecessor code

Reuse behavior only when a vertical slice needs it:

- optimistic event append and separate event/recording time;
- transactional outbox and idempotent consumers;
- deterministic chunking and hybrid retrieval;
- tenant-isolation and citation-validation tests.

Do not preserve the five-service topology, article aggregate, public Q&A
contract, MongoDB conversation model, or provisional tenant header merely to
avoid deleting code.

## Definition of done

A change is complete when its acceptance criteria are demonstrated by automated
tests or explicit inspection evidence, affected contracts and documentation are
updated, migrations work from both an empty database and the supported previous
state, security and tenant boundaries have negative tests, and limitations are
recorded in the issue or pull request.

The full contribution and handoff requirements are in
[CONTRIBUTING.md](../CONTRIBUTING.md).
