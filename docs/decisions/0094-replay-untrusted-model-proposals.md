# ADR 0094: Replay untrusted model proposals

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

M07 needs repeatable handling of model proposals without a live model adapter.
Untrusted response text cannot introduce operational facts, approval or verified
outcomes. The existing production planner already defines the admissible first
step and its minimum safeguards for a pinned case prefix.

## Decision

Add the simulation-only evaluator `ergon.simulation/model-proposal/v1`. Pin one
bounded descriptive model configuration and ordered response scripts. Each script
names the exact configuration artifact, and all material digests are verified
before parsing. Keep prompts and responses inert with no provider port or fallback.

Support only next-step suggestions naming a step, capability and existing fact and
documentary references. Strictly decode these into typed values, then compare them
against production readiness/planning and the exact pinned evidence. Under the
current account-state runtime, require the latest bound fact and the exact set of
contract documentary requirements. Documentary validity still uses attachment time.

Report malformed and unsupported responses explicitly. Invalid fixture envelopes
remain decoding errors. A consistent proposal is advisory and retains runtime
policy requirements; it cannot authorize a call, create evidence or accept proof.
Keep raw text out of the observation trace while retaining exact response hashes.

## Alternatives considered

### Introduce a live provider extension point

- Benefits: Could compare provider behavior immediately.
- Costs and risks: Credentials, mutable remote behavior and a new production API.
- Reason not selected: Scripted replay is the approved bounded scope.

### Trust the model's proposed policy and evidence

- Benefits: Fewer validation steps.
- Costs and risks: Fabricated references and self-authorized execution.
- Reason not selected: Runtime policy and existing bindings must remain authoritative.

## Consequences

### Positive

Adversarial and malformed outputs receive deterministic tests without paid services
or new dependencies. Production planning semantics remain shared.

### Negative

This measures deterministic proposal handling, not model quality. The supported
proposal vocabulary is deliberately narrow; additional fact types or proposal
semantics require a new evaluator version and corresponding reference checks.

### Neutral or follow-up

Later M07 slices consume observations for assertions, accounting and reports.
There is no combined connector/model orchestration in this evaluator.

## Compatibility and migration

New evaluator and material schema identifiers are additive. Existing readiness
and capability evaluators retain their behavior. No database, production HTTP,
contract or event migration is needed. Revert this evaluator with its consumers;
registered fixture bytes remain immutable.

## Security and operations

No live provider, connector, clock, credentials or production repository is wired
into the service. Bounded input, exact digests, strict decoding and independent
runtime checks prevent response text from becoming authority. Configuration and
response text remain untrusted even when their package integrity is valid.

## Validation

Test response order, repeated replay, configuration and response digests, malformed
and oversized output, unknown capability, wrong step, fabricated/stale references,
adversarial fields and documentary attachment cutoffs. Retain both prior evaluator
suites, repository policy, static checks and full Maven verification. Report any
Docker-backed verification gaps explicitly.
