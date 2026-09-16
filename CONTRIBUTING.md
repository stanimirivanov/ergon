# Contributing to Ergon

## TL;DR

- Deliver one coherent, verified capability per issue and pull request.
- Preserve pre-existing work and keep unrelated changes out of the diff.
- Proceed only on local, reversible assumptions; request a decision when
  behavior, compatibility, security, persisted meaning, or external state may
  materially change.
- Dependencies point inward; the domain remains free of Spring and
  infrastructure.
- Run the exact repository checks and report anything not run honestly.
- Use milestone titles `MNN - Outcome` and include the milestone in every issue.
- End every completed coding task with the prescribed issue text and
  verification report.

## Policy language and sources of truth

In this repository, **MUST** and **MUST NOT** are requirements, **SHOULD** and
**SHOULD NOT** are strong defaults whose exceptions need a recorded reason, and
**MAY** identifies an optional choice.

| Concern | Canonical source |
|:--|:--|
| Workflow, issue timing, review, and completion | This document |
| Concise contributor and agent entry point | [AGENTS.md](AGENTS.md) |
| Kotlin, Spring, architecture, tests, and operations | [engineering standards](docs/development/engineering-standards.md) |
| PostgreSQL schema and Flyway migrations | [SQL migration criteria](docs/development/sql-migrations.md) |
| Durable architecture choices | [ADRs](docs/decisions/README.md) |
| Intended delivery sequence | [milestones](docs/roadmap/milestones.md) |
| Exact module setup | The module's `README.md` and checked-in configuration |

The narrower source governs its stated concern. An accepted ADR governs the
decision it records until superseded. Surface unresolved conflicts instead of
quietly choosing the convenient rule.

## Before starting

A contributor MUST:

- inspect the branch and working tree and preserve unrelated changes;
- read the product, architecture, applicable standards, relevant module README,
  and accepted ADRs;
- define the smallest independently reviewable outcome;
- identify affected contracts, migrations, security boundaries, documentation,
  and operational behavior; and
- determine the issue workflow below.

Do not discard, overwrite, broadly reformat, or absorb unrelated work to obtain
a clean diff. Destructive actions and writes to production, third-party, or
other external state require explicit authorization.

Use an ADR when a choice affects compatibility, persistence or data meaning,
security, deployment topology, foundational technology, ownership, or multiple
applications. Cheap, local implementation choices do not need one.

## Ambiguity and escalation

Inspect relevant code, tests, docs, ADRs, fixtures, and history before asking for
clarification. A documented assumption is acceptable only when it is local,
reversible, inexpensive, within stated scope, and does not alter a public
contract, persisted meaning, security/privacy boundary, external state, or
acceptance criteria.

Stop and request a decision when missing information could materially change:

- observable behavior, scope, milestone, or acceptance criteria;
- compatibility, data meaning, security, privacy, or authorization;
- irreversible or destructive behavior;
- production, third-party, or externally visible state; or
- meaningful cost, ownership, or deployment topology.

State the exact decision, evidence checked, viable options, and consequences.

## Issue timing and milestones

Issue-first is preferred. When an issue is supplied, follow its outcome and
milestone and link the pull request. When implementation is requested without
an issue, complete the smallest coherent scope and provide proposed issue text
in the completion report. Creating an issue, milestone, or pull request is an
external write and requires explicit authority.

Milestone titles use `MNN - Short outcome`, beginning at M01. Published numbers
are never reused or renumbered. The title describes the capability available at
completion, not an activity. Every implementation issue names exactly one
milestone from [the roadmap](docs/roadmap/milestones.md).

## Required issue structure

Use a specific imperative title and this body:

```markdown
**Milestone:** MNN - Outcome

## Goal

Describe the problem and observable result.

## Scope

- Included behavior and boundaries.

## Design decisions

- Important choices, assumptions, compatibility effects, and ADR links.

## Acceptance criteria

- [ ] Observable behavior and verification evidence.
- [ ] Relevant negative and failure behavior.
- [ ] Documentation and operational effects.

## Out of scope

- Explicit exclusions and deferred work.
```

Acceptance criteria describe observable behavior or verifiable invariants, not
activities such as creating a class. Record known limitations explicitly.

## Pull-request-sized work

A pull request MUST solve one problem or deliver one coherent vertical
capability, keep the repository buildable, and include the implementation,
tests, documentation, contract changes, and migration needed to prove it.

Split independent behavior, broad cleanup, dependency upgrades, schema
redesign, unrelated formatting, and separate architecture decisions. A thin
vertical slice may legitimately touch domain, application, adapters, storage,
HTTP, and tests. Do not create placeholder abstractions, empty packages, or
future configuration.

## Development workflow

1. Select or define the issue and milestone.
2. Create a focused `codex/` branch when using Codex, or another short
   issue-oriented branch name for human work.
3. Add or update tests with the behavior.
4. Implement the smallest coherent solution.
5. Format and run the exact checks.
6. Review the complete diff for secrets, generated churn, accidental contract
   changes, assumptions, and unrelated edits.
7. Update documentation, ADRs, migration notes, and operational guidance.
8. Open a linked pull request only when authorized.
9. Produce the completion report below.

Commit subjects SHOULD be imperative and describe the observable change.
Generated output is committed only when consumers require it and regeneration
is deterministic.

## Verification and constrained environments

The required baseline is:

```powershell
.\mvnw.cmd -B -ntp verify
```

```bash
./mvnw -B -ntp verify
```

This runs compilation, tests, Ktlint, and Detekt across the reactor. Docker must
be available for Testcontainers-backed integration tests; a skipped Docker test
is not passing evidence. Narrow module or test commands are useful while
working but do not replace the applicable baseline.

When a required check cannot run, report the exact check as **not run**, the
blocking condition, checks that did run, residual risk, and where the missing
check should run. Never report a skipped or unexecuted check as passed, weaken a
check for the current environment, or hide an unrelated failure.

## Documentation

Every long document has a `## TL;DR` immediately after its title and status
metadata. Treat a document as long when it has 800 or more words, more than five
second-level sections, or is an architecture, security, operational, migration,
or end-to-end workflow guide. Templates and forms are exempt because their
headings define the content contributors must supply rather than a reading
sequence.

Public contracts, configuration, migrations, behavior, and troubleshooting
change with the code they describe. Examples SHOULD be executable or verified.
Accepted ADRs are historical records: supersede rather than rewrite them.

## Pull request description

A pull request states:

- linked issue and milestone;
- problem and resulting behavior;
- boundaries, exclusions, assumptions, and unresolved questions;
- design, compatibility, and ADR decisions;
- verification commands and outcomes, including checks not run;
- migration, rollout, rollback, security, and operational considerations; and
- known limitations and follow-up work.

Use [the checked-in template](.github/PULL_REQUEST_TEMPLATE.md).

## Review checklist

- [ ] The issue names one milestone and follows the required structure.
- [ ] The change is one coherent capability with explicit exclusions.
- [ ] Dependencies point inward and infrastructure stays out of domain policy.
- [ ] Public and persisted contracts are compatible or have an evolution plan.
- [ ] Tests cover observable success and relevant negative/failure behavior.
- [ ] Tenant scope, authorization, concurrency, idempotency, and retries are
      tested where applicable.
- [ ] Kotlin, KDoc, Spring, and SQL follow the applicable standards.
- [ ] Security, privacy, untrusted input, and secret handling were reviewed.
- [ ] API, event, schema, configuration, and operational docs are current.
- [ ] Migrations pass empty-database and supported-upgrade verification.
- [ ] Required checks passed or unavailable checks are reported honestly.
- [ ] No unrelated changes, secrets, personal data, or generated noise exist.
- [ ] An ADR is present when the choice meets the ADR threshold.

## Completion report

After every completed coding task, print:

1. the exact milestone as `MNN - Outcome`;
2. a proposed GitHub issue title;
3. a copy/paste-ready Markdown issue body using the required structure and
   matching the work actually performed;
4. assumptions, unresolved questions, and limitations; and
5. verification commands and outcomes, explicitly separated into **passed**,
   **failed**, and **not run**.

When an existing issue was supplied, repeat or amend its title and body so the
delivered scope remains auditable. The report does not imply that an external
issue or pull request was created.
