# Ergon

Ergon is an open-source resolution engineering platform. It turns organizational
knowledge into safe, testable procedures that people and AI can execute, then
records evidence of whether the requested outcome was achieved.

> **TL;DR:** Ergon models a request as an evidence-backed case, executes a
> versioned resolution contract through policy-gated tools and human approvals,
> and accepts resolution only when its outcome can be verified.

## Status

Ergon is at the rewrite boundary. The repository still contains the executable
RAG Help Center implementation that preceded it; that code is retained only as
material for incremental, pull-request-sized replacement. New work follows the
Ergon product model and is not required to preserve the old article or Q&A APIs.

## Product model

```text
request -> case graph -> evidence bundle -> resolution contract
        -> authorized run -> outcome proof -> improvement proposal
```

- A **case graph** holds the goal, observations, facts, unknowns, actions, and
  outcomes independently of any chat or ticket channel.
- An **evidence graph** connects claims, procedures, policies, sources, and
  contradictions with scope and validity.
- A **resolution contract** defines applicability, required evidence, allowed
  capabilities, approvals, branching, compensation, and proof of success.
- A **resolution run** is an append-only, replayable execution pinned to exact
  contract, policy, model, prompt, evidence, and tool-schema revisions.
- An **outcome proof** distinguishes a verified result from a sent answer or a
  closed ticket.
- An **improvement proposal** turns an unusual or failed case into a reviewable
  knowledge, contract, or regression-test change.

## Read next

- [Product](docs/product.md) — purpose, users, primitives, and non-goals.
- [Architecture](docs/architecture.md) — boundaries, runtime, trust model, and
  target topology.
- [Delivery](docs/delivery.md) — first vertical slice and staged rewrite.
- [Contracts](docs/contracts.md) — strict `v1alpha1` document shape and validation boundary.
- [Contributing](CONTRIBUTING.md) — change size, Kotlin, SQL, testing, security,
  and review standards.
- [Architecture decisions](docs/decisions/README.md) — ADR policy and the status
  of decisions inherited from RAG Help Center.

## Current build

The first Ergon implementation slice lives in `control-plane` and
`domain-kernel`. It persists an append-only case stream, a source-observation
timeline, and typed account-access facts in PostgreSQL. Its current APIs are:

- `POST /api/v1/tenants/{tenantId}/cases` opens a case from a goal and requester
  observation, returning `ETag: "1"`.
- `POST /internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations`
  appends an attributable connector observation and requires a quoted
  `If-Match` stream version.
- `GET /api/v1/tenants/{tenantId}/cases/{caseId}/timeline` returns observations
  in stream order with both occurrence and persistence times.
- `POST /internal/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states`
  binds `ACTIVE` or `LOCKED` to an existing connector observation.
- `GET /api/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states`
  returns the typed facts with their source observation and binding metadata.
- `POST /internal/v1/resolution-contracts/validate` validates a strict YAML
  contract and returns its normalized meaning without storing or executing it.
- `POST /internal/v1/tenants/{tenantId}/resolution-contracts` publishes an
  immutable normalized revision; its returned `Location` retrieves that exact
  tenant-scoped revision.

Set `ERGON_DATABASE_URL`, `ERGON_DATABASE_USERNAME`, and
`ERGON_DATABASE_PASSWORD` to run `control-plane`; Flyway creates its schema.
This slice deliberately has no authentication: tenant IDs in paths are a
temporary development boundary and must not be exposed as an authorization
mechanism. Contract registry checks and case pinning, AI-assisted binding,
broader fact types, policy, and execution remain later delivery steps. The
predecessor services remain in the reactor while behavior is replaced
incrementally.

```powershell
.\mvnw.cmd -B -ntp verify
```

```bash
./mvnw -B -ntp verify
```

See [CONTRIBUTING.md](CONTRIBUTING.md) before changing code or contracts.
