# ADR 0090: Pin simulation fixture identity

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

M07 replay and fixture registration need a shared immutable input identity before
execution or promotion exists. Recording metadata must not make repeated replay
semantically different. M06 documentary validity already uses recorded attachment
time and must not drift with the replay clock.

## Decision

Introduce a bounded framework-free simulation manifest with immutable artifact
references and a versioned length-prefixed canonical encoding. Freeze collections;
preserve script order and normalize documentary pins by unique stream version.
Reject unsupported fixture versions and sub-microsecond timestamps. Pin evaluator,
assertions, model configuration and optional budget material as well as runtime
inputs. See the exact [simulation contract](../simulation.md).

This shared foundation is consumed by M07 fixture registration and pinned replay.
No persistence, HTTP parser, execution or promotion is introduced here. Referenced
artifact validation remains the responsibility of the later consuming adapters.

## Alternatives considered

### Adopt a workflow or evaluation framework

- Benefits: Existing replay tooling and report support.
- Costs and risks: A second execution model or runtime dependency would duplicate
  current Kotlin domain rules.
- Reason not selected: Adapt recorded-input and declarative-fixture patterns from
  Temporal and Promptfoo without importing their execution engines.

### Hash ordinary serialized JSON

- Benefits: Familiar interchange format.
- Costs and risks: Serializer defaults, property order and numeric rendering can
  change identity without changing intended inputs.
- Reason not selected: A small fully specified hash preimage requires no JSON
  dependency in the domain and can have independent test vectors.

## Consequences

### Positive

Stable identities and bounded immutable inputs can be shared across later slices.
Explicit references prevent accidental live fetching or executable fixture payloads.

### Negative

A dedicated canonical encoding is a compatibility commitment requiring vectors.
A valid manifest cannot prove referenced bytes exist, are sanitized or are safe.

### Neutral or follow-up

M07-PR2 resolves registration and tenant persistence; PR3–PR7 define consuming
replay, script, assertion and budget semantics. This is not a production model SDK.

## Compatibility and migration

This is additive pure-domain functionality with no database or runtime behavior
change. Existing contracts and runs remain unchanged. Future meaning or encoding
changes require a new fixture version; never reinterpret stored v1 identities.
Reverting this slice requires reverting its consumers once they exist.

## Security and operations

Fixture data is untrusted and must never convey execution authority. Keys are
bounded identifiers, not addresses. Tenant identity participates in the hash.
Curated historical material needs independent privacy review. Construction neither
fetches material nor validates documentary claims. Unknown versions fail closed.

## Validation

Domain tests prove the independent encoding vector, identity sensitivity,
collection isolation, input bounds and temporal constraints. Repository policy
and full Maven verification remain required; unavailable checks are not passes.
