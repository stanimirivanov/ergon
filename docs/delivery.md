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

The first sub-slices define strict YAML validation and persist immutable,
tenant-scoped normalized revisions for the access-restoration contract.
Registry checks, revision pinning, selection, and execution remain separate
PR-sized behavior.

### 3. Resolution runtime

Execute deterministic steps with durable state, idempotency, retries, waits,
receipts, and verification. Complete the access-restoration happy path and
failure path with fake tools.

### 4. Policy and human authority

Add capability scopes, denial reasons, approval requests, expiry, and resolver
intervention. Prove that model output, retrieved text, and channel callers cannot
grant authority.

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
