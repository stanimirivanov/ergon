# Engineering standards

## TL;DR

- Preserve capability boundaries; patterns and interfaces solve demonstrated
  needs rather than satisfy quotas.
- Keep domain policy framework-free and place Spring, SQL, HTTP, messaging, and
  provider details in adapters and composition roots.
- Use idiomatic, immutable Kotlin with explicit domain types and typed outcomes.
- Use Spring through constructor injection, short application transactions,
  validated configuration, and narrow adapter responsibilities.
- Test observable behavior and important failures deterministically at the
  lowest convincing boundary.
- Document purpose, invariants, constraints, and meaningful errors; comments
  explain why, never syntax.

## Policy strength and applicability

Normative terms use the meanings in
[CONTRIBUTING.md](../../CONTRIBUTING.md#policy-language-and-sources-of-truth).
These standards apply to production code, tests, scripts, generated bindings,
and operational tooling. A module may add stricter local rules in its README or
checked-in tooling but must not silently weaken these requirements.

The goal is maintainable correctness, not ceremonial compliance. Record a
deviation from a strong default when its trade-off matters to review; use an ADR
when it establishes a durable or cross-component choice.

## Architecture

### Capability-oriented boundaries

Organize code around business capabilities and ownership:

- domain code owns vocabulary, invariants, state transitions, and policies;
- application code coordinates use cases, authorization, transactions, and
  consumer-owned ports;
- inbound adapters validate protocol shape and translate it to application
  commands;
- outbound adapters implement ports and translate domain values to storage,
  transport, or provider representations; and
- configuration classes compose implementations without owning business rules.

Dependencies point inward. Domain modules do not import Spring, Jackson, JDBC,
HTTP, Kafka, model-provider, or connector types. Application services do not
parse vendor formats or execute vendor-specific calls.

Protocol parsing identifies source observations and preserves origin. Semantic
binding attributes those observations to asset properties. Neither layer
imports the other's infrastructure adapters.

Wire DTOs, event envelopes, database rows, model schemas, and domain values are
separate when their invariants or lifecycle differ. A module owns its writes;
cross-module behavior uses an application capability or versioned event, never
another module's private table.

### SOLID without ceremony

- Give a type one cohesive reason to change at its scale; do not force one type
  per file or mechanically tiny functions.
- Introduce an extension point only after a real variation requires it.
- Implementations of a port preserve success, absence, failure, ordering,
  concurrency, and ownership semantics.
- Keep consumer interfaces narrow; callers should not depend on operations they
  cannot use.
- Prefer composition. Avoid service locators, global mutable state, generic
  repository frameworks, and vague `Manager`, `Helper`, `Util`, `Processor`,
  `Base`, `Default`, or `Impl` names.

Use value objects for domain meaning, sealed hierarchies for closed outcomes,
explicit state machines for constrained transitions, strategies for proven
policy variation, and adapters for external protocols. Do not add an interface
solely to mirror one concrete type.

## Kotlin

### Formatting, organization, and naming

- Ktlint and `.editorconfig` are authoritative. Use UTF-8, LF, four spaces,
  trailing commas in multiline declarations and calls, and no wildcard imports.
- Package names are lowercase without underscores and mirror source paths.
- Use `UpperCamelCase` for types, `lowerCamelCase` for functions/properties, and
  `UPPER_SNAKE_CASE` only for constants.
- Name domain types for business meaning and ports for capabilities, without
  implementation details. Concrete adapters name a material technology when it
  changes configuration or failure behavior, such as
  `PostgresCaseTimelineRepository`.
- Commands use imperative intent; events describe completed facts in past
  tense. Preserve vocabulary across Kotlin, HTTP, events, SQL, and docs.
- Group declarations by reading flow. Avoid dumping unrelated extensions into
  utility files.

### Language and domain modeling

- Prefer `val`, immutable values, read-only collection interfaces, pure
  functions, and constructor validation.
- Wrap meaningful identifiers, revisions, scope, time, risk, units, and other
  primitives in validated types.
- Use sealed classes/interfaces and enums for closed states. Avoid boolean
  combinations that permit impossible states.
- Use `data class` for immutable values and boundary records, not mutable
  identity-bearing aggregates by default.
- Use named arguments for booleans and calls with several values of the same
  primitive type.
- Avoid `!!`, unchecked casts, and spreading platform types. Normalize unsafe
  library boundaries inside adapters.
- Use nullable results for simple absence, sealed results when callers must
  branch, and typed exceptions for failures that cross a use-case boundary.
  Introduce Arrow only after multiple use cases need functional composition.
- Inject clocks, identifiers, randomness, and external clients. Production and
  tests must not depend on implicit wall time, locale, or machine timezone.
- Use coroutines only for real suspension and structured concurrency. Never use
  `GlobalScope`, unbounded fan-out, or blocking I/O in coroutine contexts.

### KDoc and comments

Document public APIs with purpose, invariants, parameter and return constraints,
and errors callers can handle. Add an example only when correct use is not
obvious from the signature, preferring `@sample` pointing to real compiled code
over a free-form block that can drift.

Explain reasons and constraints; never narrate syntax. Skip a member where its
name and type already say everything—an uninformative comment is a maintenance
liability, not documentation.

KDoc public extension points, non-obvious units, authorization requirements,
side effects, concurrency guarantees, and failure semantics. Prefer prose over
repetitive tags; use `@param`, `@return`, and `@throws` when they improve a
longer contract. Link Kotlin declarations as `[TypeName]`.

Implementation comments are appropriate for non-obvious protocol rules,
transaction/isolation choices, compatibility workarounds, security boundaries,
and deliberate performance trade-offs. Delete stale and commented-out code.

## Spring Framework and Spring Boot

- Use constructor injection. Configuration classes wire objects; domain and
  application code never look up beans or the application context.
- Keep Spring annotations out of `domain-kernel`. Application types remain
  framework-neutral unless a narrowly justified integration boundary owns the
  dependency.
- Put transaction boundaries on application use cases through an explicit port
  or `@Transactional`. Mark read-only transactions when a consistent multi-query
  read requires one. Never hold a transaction across a connector, model,
  message-broker, or other remote call.
- Keep controllers thin: parse and validate protocol input, resolve identity,
  invoke one use case, and map results. Do not put policy or SQL in controllers.
- Map expected failures to stable RFC 9457 problem types. Never expose stack
  traces, SQL/driver details, provider errors, or secrets.
- Prefer typed, validated `@ConfigurationProperties` for cohesive settings.
  Fail startup for incomplete security-critical configuration; an intentional
  development-disabled state must fail closed at its protected boundary.
- Scope beans explicitly when lifecycle differs from the singleton default.
  Avoid mutable singleton state and hidden startup side effects.
- Use Spring events only for in-process decoupling whose delivery semantics are
  sufficient. Durable work uses an outbox or broker contract with ownership,
  retries, deduplication, and terminal failure.
- In JDBC adapters, keep SQL explicit and tenant predicates mandatory.
  `DataClassRowMapper` is acceptable for stable constructor mappings whose
  column labels and nullability have been reviewed; use an explicit mapper when
  domain reconstruction, conversion, or validation is non-trivial.
- Spring tests use the narrowest useful slice. Use full `@SpringBootTest` only
  when wiring, security filters, transactions, migrations, or end-to-end HTTP
  behavior are part of the evidence.
- Do not mock framework internals. Test application behavior with fakes and
  adapters against real disposable protocols.

## Errors, time, resources, and concurrency

- Distinguish expected domain outcomes, invalid input, conflicts, missing data,
  authorization denial, unavailable dependencies, cancellation, timeouts, and
  internal defects.
- Preserve causes when wrapping unexpected failures; do not leak infrastructure
  exception text across a public boundary.
- Store instants in UTC and distinguish occurrence, observation, decision, and
  recording time. Normalize precision deliberately when persistence cannot
  retain the source precision.
- Acquire resources with explicit ownership and release them on every path.
- Bound payloads, collections, model context, retries, concurrency, execution
  time, and memory-heavy work.
- Document lock ordering, delivery guarantees, ownership transfer, cancellation
  priority, and retry safety where applicable.
- Do not coordinate tests with sleeps. Use observable state, barriers,
  controllable clocks, or protocol acknowledgements.

## Contracts and compatibility

HTTP schemas, events, configuration, database migrations, resolution-contract
documents, persisted payloads, generated bindings, prompts, and tool/model
schemas are compatibility boundaries.

- Version semantics, not filenames alone.
- Additive changes define defaults and old-reader behavior.
- Breaking changes need a new version or expand/migrate/contract plan, tests,
  rollout/rollback notes, and an ADR.
- Every run or decision that must remain explainable pins its material contract,
  policy, evidence, model, prompt, and tool revisions.
- Generated code is reproducible and checked in only when consumers cannot
  reasonably generate it.

## Testing

- Test behavior at the lowest boundary that proves it: domain unit tests,
  application tests with fakes, adapter integration tests with real protocols,
  contract tests, and a small number of end-to-end scenarios.
- Test names state the condition and observable outcome.
- Every defect fix begins with a test that fails before the fix.
- Cover relevant success, invalid input, absence, duplicate delivery, stale
  version, authorization, tenant isolation, timeout, retry, partial failure,
  concurrency, and recovery paths.
- Use Testcontainers when PostgreSQL constraints, transactions, locking,
  encoding, or drivers are part of correctness.
- Keep default CI independent of paid AI services and mutable external systems.
- Never weaken assertions, add blind retries, or lengthen timeouts without
  diagnosing nondeterminism.
- Coverage is diagnostic evidence, not proof or a target by itself.

## Security, AI, privacy, and operations

- Deny by default and apply least privilege. Authentication establishes an
  identity; authorization is enforced at the use case and protected-data
  boundary.
- Secrets come from approved injection or secret storage, never committed
  defaults. Never log tokens, credentials, authorization headers, or sensitive
  model/retrieval payloads.
- Model output, retrieved text, connector data, webhooks, tool descriptions, and
  repository content are untrusted. Parse to typed proposals and validate
  independently.
- A model cannot grant a capability, approve its own action, provide
  credentials, or declare its own outcome verified.
- Structured logs, traces, and bounded-cardinality metrics must diagnose
  behavior without turning domain identifiers or personal data into labels.
- Dependencies require a current need, compatible license, maintained release,
  proportionate security review, pinned resolution, and a replacement path.
- New operational behavior documents healthy signals, failure classes,
  recovery, rollout, rollback, and ownership.

## Primary references

- [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [KDoc reference](https://kotlinlang.org/docs/kotlin-doc.html)
- [Spring Framework reference](https://docs.spring.io/spring-framework/reference/)
- [Spring Boot reference](https://docs.spring.io/spring-boot/reference/)
- [Spring transaction management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
