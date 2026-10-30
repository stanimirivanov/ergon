# Domain kernel

## Purpose

`domain-kernel` contains Ergon's framework-free domain vocabulary, validated
values, policies, and state transitions for cases, contracts, identity,
authority, capability execution, retries, proof, and escalation.

Simulation assertions compare explicit observed traces against independently
authored expectations with pass/fail/error/unsupported results. They do not execute
fixtures or authorize promotion; see [assertion semantics](../docs/simulation.md#typed-scenario-assertions).

Deterministic budgets use exact currency-nano pricing and explicit usage, preserving
unknown measurements and overflow as non-passing outcomes.

## Boundary

- May depend on the Kotlin/JDK standard library and domain-focused libraries
  deliberately approved for this module.
- Must not depend on Spring, JDBC, HTTP, Jackson, Kafka, model providers,
  connectors, or persistence annotations.
- Exposes validated domain values to application modules; it does not expose
  transport DTOs or database rows.

## Verify

```bash
./mvnw -B -ntp -pl domain-kernel test
```

The full repository `verify` remains required before
merge. See [engineering standards](../docs/development/engineering-standards.md).
