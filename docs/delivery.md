# Ergon delivery strategy

## TL;DR

Prove Ergon with one complete access-restoration case before adding channels,
providers, ingestion formats, or deployment topology. Each change delivers one
reviewable capability and leaves the repository working. Milestone scope and
progress live in the [roadmap](roadmap/milestones.md); implemented behavior lives
in [current state](development/current-state.md).

## First vertical slice

The reference scenario restores access to a fictional SaaS workspace:

1. A requester reports a sign-in failure.
2. Ergon opens a case and identifies missing evidence.
3. A deterministic identity connector observes account state and entitlements.
4. The runtime pins an applicable access-restoration contract and policy.
5. A verified human supplies approval when the effective safeguards require it.
6. The connector executes once under a scoped grant and idempotency key.
7. A later, independent observation proves whether access was restored.
8. Only accepted outcome proof resolves the case.

The slice runs locally without paid services. Tests use deterministic model,
clock, identity, and connector adapters where production integrations would
otherwise make behavior nondeterministic.

## Delivery boundaries

- Build product behavior in `domain-kernel` and `control-plane`; maintain legacy
  modules only until their useful behavior is replaced.
- Keep probabilistic interpretation separate from deterministic authorization,
  execution, and verification.
- Introduce abstractions when the current capability needs them, not to reserve
  space for possible future providers or deployment shapes.
- Prefer one end-to-end contract with negative-path tests over several partial
  layers with no observable behavior.
- Record compatibility, durability, security, data-meaning, and topology choices
  in an ADR when they cross the threshold in
  [the decision guide](decisions/README.md).

## Extraction policy

Reuse predecessor behavior only when an active vertical slice requires it.
Likely candidates are optimistic event append, separate event and recording
time, transactional outbox behavior, idempotent consumers, deterministic
chunking, hybrid retrieval, and tenant-isolation tests.

Do not preserve the predecessor service topology, article aggregate, public Q&A
contract, MongoDB conversation model, or provisional tenant header merely to
avoid replacing code. A migrated capability must conform to Ergon's current
domain and trust boundaries.

## Definition of done

A change is complete when its acceptance criteria are demonstrated by automated
tests or explicit inspection evidence; affected contracts and documentation are
updated; relevant migrations work from an empty database and the supported
previous state; security and tenant boundaries have negative tests; and any
remaining limitation is recorded in the issue and pull request.

The complete workflow and completion-report contract are in
[CONTRIBUTING.md](../CONTRIBUTING.md).
