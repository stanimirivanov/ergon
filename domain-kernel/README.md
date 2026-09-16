# Domain kernel

## Purpose

`domain-kernel` contains Ergon's framework-free domain vocabulary, validated
values, policies, and state transitions for cases, contracts, identity,
authority, capability execution, retries, proof, and escalation.

## Boundary

- May depend on the Kotlin/JDK standard library and domain-focused libraries
  deliberately approved for this module.
- Must not depend on Spring, JDBC, HTTP, Jackson, Kafka, model providers,
  connectors, or persistence annotations.
- Exposes validated domain values to application modules; it does not expose
  transport DTOs or database rows.

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl domain-kernel test
```

From Bash, use `./mvnw`. The full repository `verify` remains required before
merge. See [engineering standards](../docs/development/engineering-standards.md).
