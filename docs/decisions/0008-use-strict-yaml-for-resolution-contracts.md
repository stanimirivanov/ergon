# 0008. Use strict YAML for resolution contracts

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

The runtime needs a Git-reviewable contract format before it can persist, pin,
or execute a revision. General workflow languages expose behavior Ergon cannot
yet validate or authorize, while an unconstrained YAML object would make field
typos and parser ambiguity part of the security boundary.

## Decision

Define `ergon.dev/resolution-contract/v1alpha1` as a small, closed YAML schema.
It declares contract identity and revision, one applicability equality,
required evidence, ordered capability steps with risk and approval, and one
outcome-proof equality.

Parse with safe YAML types and explicit size and nesting limits. Reject
collection aliases, duplicate keys, unknown fields, missing fields, wrong
scalar types, invalid domain values, and cross-field invariant violations.
Return normalized domain meaning only; validation does not persist, select,
authorize, or execute.

Treat the schema identifier and field meanings as a published compatibility
boundary. Incompatible evolution introduces a new identifier rather than
changing `v1alpha1` in place.

## Alternatives

A general DAG or workflow language was rejected until execution semantics and
policy prove which constructs are needed. JSON was viable but less convenient
for Git-authored contracts. Permissive YAML was rejected because ignored fields,
duplicates, aliases, and implicit extensibility would make reviews unreliable.

## Consequences

Contracts are readable in code review and deterministic to interpret. Typos do
not become ignored behavior, high-risk actions cannot declare that no approval
is needed, and a declared capability remains only a request for later policy
evaluation.

The first schema has only equality conditions and sequential action steps. It
does not yet express branching, retries, waits, compensation, typed capability
inputs, policy rules, or proof acceptance windows. Later behavior must be added
from demonstrated runtime needs and may require a new schema version.

## Verification

Domain tests cover cross-field invariants. Decoder and HTTP tests parse the
published example, reject unsafe or ambiguous input, assert stable violations,
and verify that the validation operation appears in OpenAPI.
