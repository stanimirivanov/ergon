# Ergon

Ergon is an open-source resolution engineering platform. It turns evidence and
organizational knowledge into policy-gated procedures that people and AI can
execute, then requires proof that the requested outcome was achieved.

## TL;DR

- Ergon models a problem as a case, not as a chat transcript or ticket.
- Versioned resolution contracts describe evidence, actions, approvals, and
  outcome proof.
- Models may propose; deterministic policy and capability boundaries authorize.
- A case is verified as resolved only after its pinned proof condition succeeds.
- The active implementation is a Kotlin/JVM modular monolith being extracted
  incrementally from the repository's predecessor services.

## Product loop

```text
request -> case graph -> evidence bundle -> resolution contract
        -> authorized run -> outcome proof -> improvement proposal
```

Read [the product definition](docs/product.md) for the vocabulary, positioning,
and non-goals, and [the architecture](docs/architecture.md) for module and trust
boundaries.

## Repository map

| Path | Role | Status |
|:--|:--|:--|
| [`domain-kernel`](domain-kernel/README.md) | Framework-free Ergon domain model | Active |
| [`control-plane`](control-plane/README.md) | Ergon case, contract, policy, and run APIs | Active |
| [`ingestion-service`](ingestion-service/README.md) | Predecessor article ingestion service | Legacy; maintained while replaced |
| [`embedding-worker`](embedding-worker/README.md) | Predecessor article embedding projection | Legacy; maintained while replaced |
| [`retrieval-service`](retrieval-service/README.md) | Predecessor hybrid retrieval service | Legacy; maintained while replaced |

The moving inventory of implemented behavior belongs in
[current state](docs/development/current-state.md), not in this entry point.

## Quickstart

Requirements:

- JDK 25;
- Docker Desktop or another Testcontainers-compatible Docker runtime for
  PostgreSQL integration tests; and
- Git with LF line endings preserved by EditorConfig.

Run the required repository verification:

```powershell
.\mvnw.cmd -B -ntp verify
```

```bash
./mvnw -B -ntp verify
```

Format Kotlin before committing:

```powershell
.\mvnw.cmd -B -ntp ktlint:format
```

The root [`Makefile`](Makefile) provides equivalent convenience targets on
systems with `make`; Maven Wrapper commands remain canonical. See the active
module READMEs for runtime configuration and local startup.

## Start here

- [Documentation map](docs/README.md)
- [Contributor workflow](CONTRIBUTING.md)
- [Tool-facing working agreement](AGENTS.md)
- [Engineering standards](docs/development/engineering-standards.md)
- [SQL and Flyway criteria](docs/development/sql-migrations.md)
- [Implementation milestones](docs/roadmap/milestones.md)
- [Architecture decisions](docs/decisions/README.md)
- [Security policy](SECURITY.md)
- [Code of conduct](CODE_OF_CONDUCT.md)

Ergon is pre-release. Internal endpoints and local defaults are development
boundaries, not production authorization or deployment guidance.
