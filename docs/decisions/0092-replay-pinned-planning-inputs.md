# ADR 0092: Replay pinned planning inputs

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

Registered fixtures preserve complete bytes but cannot yet produce a planning
observation. Reimplementing readiness for simulation would let tests certify a
second algorithm instead of the runtime. Documentary validity must continue to
use the recorded attachment cutoff established in M06.

## Decision

Decode verified packages into bounded curated case-prefix, policy and documentary
validity material. Feed the production readiness and planning services through
isolated read-only implementations of their existing ports. Reuse the existing
contract and case-event decoders and case aggregate. Do not change production
service behavior to accommodate simulation.

Pin the planning evaluator by the exact material identifier
`ergon.simulation/readiness-policy/v1`. Unknown evaluator/material/event versions
and execution scripts yield explicit unsupported observations. Malformed or
inconsistent pinned inputs fail validation. A planning observation does not assess
fixture expectations, assertion or budget material and is not a passing evaluation.

Documentary summaries are curated replay inputs, not production bundle
certifications. Bind each summary to the exact case attachment and its recorded
instant, while letting production readiness assess validity at that instant.

## Alternatives considered

### Implement a separate pure simulation readiness algorithm

- Benefits: Fewer application-port adapters.
- Costs and risks: Duplicated precedence, binding projection and policy behavior.
- Reason not selected: Existing application services already accept injectable
  read ports and deterministic policy values.

### Run simulations against production persistence

- Benefits: Existing storage assembly.
- Costs and risks: Mutable/latest state, tenant leakage and accidental writes.
- Reason not selected: The fixed complete package can supply all planning inputs
  without database access or a new execution environment.

## Consequences

### Positive

Simulation exercises production planning decisions without live dependencies.
Case ordering, contract pins and recorded attachment cutoffs are checked explicitly.

### Negative

Material schemas and the evaluator identifier become compatibility commitments.
Curated summaries do not prove that a production historical export was authentic
or correctly sanitized. The first evaluator supports planning only.

### Neutral or follow-up

M07-PR4 and PR5 add execution and proposal behavior through explicit supported
interfaces. Assertion scoring, budgets and durable reports remain later slices.

## Compatibility and migration

No database, production API, contract or event migration is introduced. Material
schema or evaluator meaning changes require new identifiers; never reinterpret
an existing fixture silently. The application bean is additive and can be removed
with its consumers. Existing fixture registration remains unchanged.

## Security and operations

No remote fetch, production write, connector, wall-clock read or random identity
source is available to the replay service. Package digests are rechecked before
material interpretation. Read adapters enforce exact tenant/case/version scope;
write methods throw. Unknown behavior cannot silently become success.

## Validation

Use deterministic offline tests for repeated results, invalid prefixes, missing
and latest-bound evidence, policy strengthening/denial and documentary expiry at
attachment. Keep repository policy and full Maven verification; report unavailable
Docker-backed checks explicitly. See [material formats](../simulation.md#material-formats).
