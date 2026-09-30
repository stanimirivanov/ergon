# ADR 0042: Enforce repository documentation policy

- Status: Proposed
- Date: 2026-09-29
- Milestone: M01 - Resolution foundation
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

Ergon has concise agent guidance, detailed contribution and engineering rules,
module-local setup, ADRs, milestones, GitHub templates, static analysis, and
Docker-backed PostgreSQL verification. These provide strong feed-forward
guidance, but most repository and documentation contracts are checked only by
review. A broken internal link, weakened template, skipped ADR number, or
renumbered milestone can therefore remain internally consistent in prose while
silently losing an intended obligation.

The full Maven reactor is also too expensive to be the only feedback loop for a
documentation or harness edit. Contributors need progressive task discovery
and a cheap deterministic sensor before compilation and database-backed tests.
Diagnostics must be useful on Windows and Unix and must tell a contributor how
to correct a failure rather than merely report that a pattern did not match.

Published history cannot be reformatted to fit a new checker. ADRs 0001–0005
use predecessor formats, 0006–0028 use older metadata and template variants,
0029–0031 are transitional, and 0032–0041 are close to the current template but
retain their published heading and metadata forms. Those records remain valid
historical evidence.

## Decision

Add a tooling-only Maven module at `tools/repository-policy`. It uses Kotlin and
CommonMark parsing with GitHub-flavored-table support; it does not add a runtime
dependency to a deployable application module.

The module provides a deterministic repository-policy check that validates:

- repository-local Markdown links, anchors, exact path casing, and repository
  containment;
- visible, non-empty TL;DR sections for qualifying non-ADR guides;
- ADR filenames, headings, required sections, lifecycle metadata,
  supersession, numbering, index coverage, and a retained published high-water
  mark;
- milestone names, numbering, short messages, matching boundary sections, and
  a retained published high-water mark; and
- canonical issue and pull-request template structure, prompts, commands, and
  checklist obligations.

Stable contract shapes and high-water constants live in checker code rather
than being derived from the prose or templates they protect. ADR 0042 is the
first record required to follow the current ADR template exactly. Explicit
legacy cutoffs preserve the published structures of ADRs 0001–0041 without
allowing new records to copy them. The current high-water marks are ADR 0042
and milestone M08; deleting the newest entry does not make either number
available for reuse.

Diagnostics are sorted and include the repository-relative path and line, a
stable rule identifier, the violated rule, the allowed shape or likely
correction, and a link to canonical policy. Focused tests cover positive,
negative, and anti-evasion cases, including hidden comments, raw HTML, image
alternative text, code fences, moved prompts, changed commands, synchronized
policy weakening, and path casing on case-insensitive filesystems.

Document the harness as four feedback tiers:

- T0 routes a task to applicable guidance, module ownership, and decisions;
- T1 runs repository policy without Docker, PostgreSQL, or external services;
- T2 runs the complete Maven reactor, including Docker-backed tests; and
- T3 records specialized migration, Compose, security, deployment, recovery,
  or operational evidence required by the changed boundary.

CI runs T1 before T2 and disables persisted credentials during checkout. The
repository-policy check is also bound to the tooling module's Maven `verify`
phase, so the full reactor retains it as part of the complete gate.

This decision does not claim an architecture fitness sensor. Architecture
rules continue to rely on module boundaries, tests, review, and engineering
standards until a separate focused change designs and validates an exhaustive
dependency check.

## Alternatives considered

### Keep repository contracts review-only

- Benefits: no checker code or build dependency.
- Costs and risks: drift remains easy to miss, reviews repeatedly inspect
  mechanical structure, and prose plus templates can be weakened together.
- Reason not selected: stable repository contracts are deterministic and
  benefit from faster, consistent feedback.

### Validate Markdown with line-oriented regular expressions

- Benefits: small initial implementation and no Markdown parser.
- Costs and risks: source text differs from rendered structure; comments,
  images, HTML, and code fences create false positives or easy evasion.
- Reason not selected: CommonMark parsing models visible document structure
  and supports focused structural diagnostics.

### Combine architecture, supply-chain, and external-link enforcement

- Benefits: one broad harness initiative and one aggregate command surface.
- Costs and risks: different network, false-positive, ownership, and design
  semantics would make this change harder to review and diagnose.
- Reason not selected: repository policy is independently useful. Architecture
  fitness, vulnerability and license policy, and external availability require
  separate decisions and evidence.

## Consequences

### Positive

Contributors get a concise task route, fast deterministic feedback, and
actionable diagnostics before expensive verification. ADR, milestone, and
review-template history cannot silently disappear or drift. CI failures are
isolated earlier, and checkout credentials are not retained after source
retrieval.

### Negative

The repository owns another tooling module, parser dependency, fixture suite,
and compatibility policy. Intentional changes to controlled structures require
coordinated checker, test, documentation, and template updates. A first Maven
run may still need network access to populate its local dependency cache.

### Neutral or follow-up

Historical ADR formats remain readable exceptions rather than being rewritten.
External-link health, factual freshness, runtime behavior, architecture
fitness, supply-chain policy, and harness-health automation remain separate
concerns.

## Compatibility and migration

No runtime API, database schema, persisted data, or deployment topology changes.
The new module joins the Maven reactor and existing local and CI verification
therefore includes repository policy. Contributors may run the focused module
command first; the full wrapper command remains the completion baseline.

Existing ADRs 0001–0041 are admitted through explicit bounded legacy rules.
New ADRs start at 0043 and use the current template. Existing milestones remain
M01–M08; a future milestone starts at M09 even after M08 is completed,
deprecated, or superseded.

## Security and operations

The checker reads repository files and does not need credentials, Docker,
PostgreSQL, production data, or external services. Paths are resolved within
the repository and links that escape it are rejected. CI uses read-only content
permissions and `persist-credentials: false` so the checkout does not leave a
write-capable credential for later build steps.

The checker must not execute document content or follow external URLs. Its
diagnostics must avoid printing secrets or unrestricted file content. Tooling
dependencies follow the same pinned-resolution and review expectations as
other build dependencies.

## Validation

Focused unit and integration fixtures demonstrate every live rule and its
anti-evasion cases on repository-relative paths. The focused module command
passes against the live tree on Windows and CI's Unix environment. Full Maven
verification proves the checker remains part of the reactor without changing
runtime behavior, and CI ordering demonstrates that repository policy fails
before Docker-backed verification.
