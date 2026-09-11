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
- [Contributing](CONTRIBUTING.md) — change size, Kotlin, SQL, testing, security,
  and review standards.
- [Architecture decisions](docs/decisions/README.md) — ADR policy and the status
  of decisions inherited from RAG Help Center.

## Current build

Until the first implementation-baseline change replaces the Maven reactor, the
legacy code continues to use Java 25, Kotlin, Spring Boot, Maven, PostgreSQL,
Kafka, Redis, and MongoDB.

```powershell
.\mvnw.cmd -B -ntp verify
```

```bash
./mvnw -B -ntp verify
```

See [CONTRIBUTING.md](CONTRIBUTING.md) before changing code or contracts.
