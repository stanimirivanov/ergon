# Contributing to Ergon

> **TL;DR:** Deliver one coherent vertical capability per pull request, keep the
> domain independent of infrastructure, encode invariants at the right boundary,
> evolve schemas and contracts forward, and attach evidence that the change
> works—including failure and tenant-isolation paths.

## Before changing code

Read [the product definition](docs/product.md),
[architecture](docs/architecture.md), and relevant
[ADRs](docs/decisions/README.md). If implementation and documentation disagree,
do not silently choose one: correct the local error or propose an ADR when the
decision is architectural.

## Change size

Each change should be reviewable as one pull request and deliver one coherent,
verified capability. Avoid creating unused abstractions for planned components.

An issue or pull request should state:

- the problem and resulting behavior;
- boundaries and deliberate exclusions;
- architecture decisions or contract changes;
- verification evidence and remaining limitations.

Prefer a thin end-to-end slice over completing one technical layer for future
use. A slice may introduce a domain rule, application use case, adapter, schema,
and test together when all are necessary to demonstrate one behavior.

Split changes that combine independent behavior, broad cleanup, dependency
upgrades, schema redesign, or unrelated formatting. Do not hide generated or
mechanical churn inside a behavioral change.

## Required issue structure

Every coding task ends with a proposed GitHub issue title and Markdown body
containing these sections:

```markdown
## Goal

## Scope

## Design decisions

## Acceptance criteria

## Out of scope
```

Acceptance criteria describe observable behavior and verification, not internal
activity such as "create a class." Record known limitations explicitly. Link
commits and the pull request to the issue; do not encode the full design only in
commit messages.

## Architecture

Dependencies point inward: applications and adapters may depend on application
and domain modules; the domain remains free of infrastructure dependencies.
Protocol parsing identifies source observations. Semantic binding attributes
those observations to asset properties. Neither layer imports the other layer's
infrastructure adapters.

Additional rules:

- Organize by domain capability, then by `domain`, `application`, and `adapter`
  boundaries where those distinctions add value.
- Domain code has no Spring, JPA, Jackson, HTTP, Kafka, SQL, model-provider, or
  connector types or annotations.
- Application code coordinates use cases and transactions through ports. It
  does not parse wire formats or issue vendor-specific calls.
- Inbound adapters validate protocol shape and map it to application commands.
  Outbound adapters implement ports and map domain values to infrastructure.
- Transport DTOs, database records, event envelopes, model output schemas, and
  domain objects are distinct. Avoid annotations that make one class serve all
  boundaries.
- A module owns its writes. Cross-module access uses an application port or a
  versioned event, not direct table access.
- Add a module, service, database, queue, or cache only for behavior required by
  the current change and explain its failure and ownership model.

Use an ADR for choices that affect compatibility, durability, security, data
meaning, deployment topology, or more than one application. Accepted ADRs are
historical records; supersede them with a new ADR instead of rewriting them.

## Kotlin conventions

### Formatting and organization

- Follow the official Kotlin coding conventions. Ktlint and `.editorconfig` are
  authoritative for mechanical style; do not debate formatter output in review.
- Use four spaces, no tabs, UTF-8, LF endings, trailing commas for multiline
  declarations and calls, and no wildcard imports.
- Package names are lowercase without underscores. A source path mirrors its
  package. Name a single-type file after its primary type; otherwise use a
  precise role name and avoid suffixes such as `Util`, `Helper`, or `Manager`.
- Keep closely related declarations together while files remain readable. Do
  not create one-file abstractions or global extension-function collections.
- Group members by reading flow and behavior, not alphabetically or by
  visibility.

### Naming

- Name domain types for business meaning: `CaseGoal`, `SourceObservation`, and
  `ResolutionContract`, not storage or transport representations. Commands use
  imperative intent; events use completed, past-tense facts.
- Use `UpperCamelCase` for types, `lowerCamelCase` for functions and properties,
  and `UPPER_SNAKE_CASE` only for constants. Package names are lowercase,
  concise, and describe capabilities rather than technical layers alone.
- Name ports for the capability they provide, without an implementation detail:
  `CaseTimelineRepository`, not `PostgresRepository`. Name concrete adapters
  with a material technology or protocol when that dependency affects
  configuration, failure behavior, queries, or operations, such as
  `PostgresCaseTimelineRepository` or `HttpIdentityConnector`.
- Do not add `Impl`, `Default`, `Base`, `Common`, or `Abstract` merely to satisfy
  an interface. Use a role that distinguishes the implementation. Avoid
  `Manager`, `Helper`, `Util`, and `Processor` when a precise capability name is
  available.
- Preserve established domain vocabulary across Kotlin, HTTP, events, and SQL.
  Do not use synonyms for the same concept or reuse one term for different
  concepts. Spell out unfamiliar abbreviations; capitalize common initialisms
  as words in identifiers (`HttpClient`, `UuidGenerator`).
- Test names state the condition and observable result. Migration, constraint,
  index, and trigger names identify their object and purpose; use `pk_`, `uq_`,
  `fk_`, `ck_`, `ix_`, and `trg_` prefixes consistently.

Format intentionally before committing:

```powershell
.\mvnw.cmd ktlint:format
.\mvnw.cmd -B -ntp verify
```

```bash
./mvnw ktlint:format
./mvnw -B -ntp verify
```

### Language and domain modeling

- Prefer `val`, immutable value objects, read-only collection interfaces, and
  pure functions. Confine mutation to aggregates and adapter internals.
- Model identifiers, revisions, money, risk, tenant scope, and other meaningful
  primitives as validated types rather than interchangeable strings or numbers.
- Use sealed hierarchies for closed state and outcome sets. Make illegal states
  difficult to construct; do not spread boolean combinations through APIs.
- Use `data class` for values and boundary records. Do not use generated
  structural equality for identity-bearing mutable aggregates without a
  deliberate reason.
- Use named arguments when a call contains booleans or multiple values of the
  same primitive type. Prefer default arguments to overloads that only supply
  defaults.
- Avoid `!!`, unchecked casts, and platform types in production code. If an
  external boundary cannot provide null safety, normalize it in its adapter.
- Domain violations are explicit values or typed exceptions with stable
  meaning. Do not expose framework or vendor exceptions across a port.
- Model an expected outcome with a sealed result type when callers must branch
  on it. Use nullable results for simple absence. Add a functional result
  library such as Arrow only when several use cases need its composition and
  interoperability benefits; do not introduce it to wrap one lookup.
- Inject clocks, ID generators, randomness, and provider clients. Tests must not
  depend on wall-clock timing, random identifiers, locale, or machine timezone.
- Use coroutines only when suspension is real and useful. Preserve structured
  concurrency; never use `GlobalScope`, blocking calls in coroutine contexts, or
  unbounded parallelism.

### Documentation

- Document public APIs with purpose, invariants, parameter and return
  constraints, and errors.
- Add an example only when correct use is not obvious from the signature alone.
  Prefer `@sample` links to real, compiled sample code over free-form blocks
  that can drift out of sync.
- Explain reasons and constraints; never narrate syntax. Skip members where the
  name and type already say everything there is to say: an uninformative
  comment is a maintenance liability, not documentation.
- KDoc public extension points, domain invariants, non-obvious units,
  authorization expectations, side effects, concurrency guarantees, and
  failure semantics. Document `@throws` only for failures the caller can
  meaningfully handle.
- Prefer self-explanatory code for mechanics. Add implementation comments for
  non-obvious protocol rules, transaction or isolation choices, compatibility
  workarounds, security boundaries, and deliberate performance trade-offs.
- Link Kotlin declarations with `[Name]`. Prefer prose over repetitive `@param`
  and `@return` tags; use tags when a lengthy explanation reads better that way.
- Keep compiled KDoc samples short, deterministic, and aligned with the
  supported API; do not duplicate tutorials in source comments.
- Comments do not excuse unclear names or oversized functions. Delete stale and
  commented-out code. Update or remove a comment in the same change that makes
  it inaccurate.
- User-visible APIs, events, configuration, migrations, and operational behavior
  require documentation in the same pull request.

### Spring and adapters

- Prefer constructor injection. Configuration performs wiring; domain objects do
  not look up dependencies.
- Keep transactions at application use-case boundaries and make propagation
  intentional. Do not hold a database transaction open across slow model or
  remote tool calls.
- Validate untrusted input at the adapter and enforce business invariants again
  in the domain.
- Map errors to stable RFC 9457 problem types at HTTP boundaries. Do not return
  stack traces, provider messages, or sensitive data.
- Configuration is typed and validated at startup. Secrets come from approved
  secret providers or environment injection and never from committed defaults.

## Testing

- Test behavior at the lowest boundary that proves it. Favor domain unit tests,
  application tests with fakes, adapter integration tests with real protocols,
  and a small number of end-to-end scenarios.
- Use descriptive backtick test names that state condition and result. Keep
  arrange/act/assert or given/when/then structure visible without ceremonial
  comments.
- Every defect fix begins with a test that fails for the reported behavior.
- Cover success, rejection, retry, duplicate delivery, stale version, timeout,
  and partial-failure behavior where relevant.
- Tenant-aware code requires a positive same-tenant test and a negative
  cross-tenant test at the persistence or retrieval boundary.
- External actions require idempotency and retry tests. Policy gates require
  denial tests. Outcome verification must prove that performing an action alone
  cannot close a case.
- Use Testcontainers for PostgreSQL, Kafka, or other protocol behavior that a
  mock cannot faithfully represent. Keep the default CI path independent of
  paid AI services.
- Tests must be deterministic and parallel-safe. Never weaken an assertion,
  add retries, or increase timeouts without identifying the underlying source
  of nondeterminism.

## SQL schema criteria

- PostgreSQL and Flyway own the production schema. Disable ORM schema creation
  and mutation outside disposable tests.
- Write SQL keywords and built-in data types in uppercase and identifiers in
  unquoted `lower_snake_case`. Keep one major clause per line and indent
  continued column lists, constraints, predicates, and references consistently.
  Use blank lines to separate conceptual blocks, not every clause.
- Use plural table names, singular column names, and descriptive, stable
  constraint and index names.
- Every table has an explicit primary key. Use application-generated UUIDs for
  externally referenced domain identities and `generated ... as identity` for
  internal surrogate sequences; do not introduce `serial`.
- Prefer `text` unless a length is a domain invariant. Enforce real limits with
  a named `check` constraint and the domain model.
- Use `timestamptz` for instants and store them in UTC. Distinguish domain event
  time from persistence time. Use `date` or local time types only when the
  business meaning is genuinely calendar-local.
- Use `numeric` for exact quantities such as money and store currency or unit
  explicitly. Do not use floating-point types for exact business values.
- Columns are `not null` by default. A nullable column must have one documented
  meaning; do not overload null as unknown, inapplicable, redacted, and deleted.
- Enforce stable invariants with primary keys, foreign keys, unique constraints,
  and checks. Application validation improves errors but does not replace
  database integrity.
- Include `tenant_id` in tenant-owned uniqueness and foreign-key relationships
  so the schema cannot create a cross-tenant association. Every access path must
  retain the tenant predicate.
- Index foreign-key and filter columns when a demonstrated query needs them;
  PostgreSQL does not automatically index the referencing side of a foreign
  key. Justify composite column order and partial-index predicates with the
  target query.
- Keep frequently queried, constrained, or joined fields relational. Use
  `jsonb` for genuinely open or versioned payloads, with stable envelope fields
  as columns. JSON is not a substitute for schema design.
- Event and audit rows are append-only. Corrections append facts or create new
  revisions; they do not rewrite history.
- When a `CHECK` constraint mirrors a Kotlin enum or sealed set, adding a domain
  value requires a paired migration and a persistence test. Document this
  compatibility coupling near the constraint when it is not obvious.
- Store secrets nowhere in application tables unless the encrypted-secret
  design has an accepted ADR. Minimize personal data and define retention and
  deletion behavior for every new sensitive field.

## SQL migration criteria

- Use Flyway versioned SQL migrations named
  `V<UTC timestamp>__<lower_snake_case_description>.sql`, for example
  `V20260911143000__create_case_event_store.sql`. A timestamp prevents version
  collisions across concurrent branches.
- Never edit, rename, reorder, or remove a versioned migration after it has
  reached a persistent shared environment. Add a new migration and roll forward.
- Keep one coherent schema or data transition per migration. Migrations and the
  application changes that depend on them belong to the same delivery plan.
- Start a non-trivial migration with a compact comment describing its data
  model, authoritative versus derived tables, mutation rules, and required
  write ordering. Comment decisions that are not apparent from the DDL, such as
  intentionally redundant uniqueness required by a tenant-safe foreign key.
- Add `COMMENT ON TABLE` and selective `COMMENT ON COLUMN` statements for
  durable domain meaning, null semantics, authoritative/derived status, and
  timestamps whose business meaning is not obvious. Do not translate every
  identifier into prose.
- Prefer SQL migrations. Use a code migration only when SQL cannot express the
  transformation safely; make its checksum and replay behavior explicit.
- Make transaction behavior explicit. A non-transactional operation such as
  `create index concurrently` belongs in its own migration with documented
  recovery steps.
- Design breaking changes as expand, migrate, verify, and contract across
  compatible releases. Do not rename/drop a live column in the same deployment
  that stops writing its predecessor.
- Estimate table scans, locks, index-build cost, WAL growth, and backfill volume.
  Large backfills are resumable, observable, and separated from long-held schema
  locks.
- Do not use `if exists` or `if not exists` to conceal unexpected drift in a
  versioned migration. Fail loudly unless tolerance is an intentional,
  documented compatibility requirement.
- Seed only deterministic reference data required by the application. Examples,
  demo tenants, and test fixtures do not belong in production migrations.
- Test migration from an empty database and from the latest supported released
  schema. Run Flyway validation and relevant integration tests. For destructive
  transitions, record backup, restore, roll-forward, and application rollback
  considerations in the issue or ADR.

## APIs, events, and resolution contracts

- Treat published HTTP schemas, event envelopes, contract DSL, persisted event
  payloads, and tool schemas as compatibility boundaries.
- Version semantics, not merely URLs or filenames. Never change the meaning of a
  published field in place.
- Additive changes need explicit defaults and old-reader behavior. Breaking
  changes need a new version, migration or coexistence plan, contract tests, and
  an ADR.
- Commands express intent; events describe completed facts in past tense.
- A resolution run pins exact contract, policy, prompt, model, evidence, and
  tool-schema revisions so it remains explainable and replayable.

## AI and tool safety

- Model output, retrieved text, connector data, and tool descriptions are
  untrusted. Parse into a typed proposal and validate independently.
- A model cannot grant a capability, approve its own action, supply credentials,
  or declare its own result verified.
- Every tool declares input/output schemas, side effects, risk, required scope,
  timeout, retry safety, and idempotency behavior.
- Separate read observations from write actions. Prefer independent outcome
  verification after a write.
- Store source references and material decision factors, not hidden
  chain-of-thought.
- Prompt and retrieval changes require evaluation fixtures. Model/provider
  changes must be replaceable behind ports and compared against the same cases.

## Security, privacy, and operations

- Default to least privilege and deny by default. Authentication establishes an
  actor; authorization is checked at the use case and protected data boundary.
- Never log credentials, tokens, raw authorization headers, prompt secrets, or
  unnecessary personal data. Treat correlation IDs as identifiers, not access
  grants.
- Bound request sizes, collection sizes, model context, retries, concurrency,
  and external call duration.
- New background work defines ownership, deduplication, retry classification,
  dead-letter or terminal-failure behavior, and operator recovery.
- New behavior exposes enough structured telemetry to identify failure without
  using high-cardinality domain identifiers as metric labels.
- Dependencies require a demonstrated current need, compatible license,
  maintained release, security review proportionate to risk, and a removal or
  replacement path at an application port.

## Pull request checklist

- [ ] The change delivers one coherent capability and matches its issue.
- [ ] Boundaries, exclusions, decisions, and limitations are documented.
- [ ] Domain dependencies point inward; no infrastructure leaked into domain.
- [ ] Tests cover observable success and relevant negative/failure behavior.
- [ ] Tenant, authorization, idempotency, and outcome-proof invariants are
      tested where applicable.
- [ ] API, event, DSL, schema, configuration, and operational docs are updated.
- [ ] Migrations pass empty-database and supported-upgrade verification.
- [ ] Formatting, static analysis, and `verify` pass.
- [ ] No secrets, personal test data, generated output, or unrelated changes are
      included.
- [ ] An ADR is present when the decision meets the ADR threshold.

## Primary references

- [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [KDoc reference](https://kotlinlang.org/docs/kotlin-doc.html)
- [Flyway versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)
- [PostgreSQL data types](https://www.postgresql.org/docs/current/datatype.html)
