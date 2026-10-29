# Coding harness

## TL;DR

- Start with the task-routing table; load detailed guidance only for the
  boundaries the change touches.
- Use T0 through T3 feedback from the cheapest relevant loop outward.
- Run `./mvnw -B -ntp -pl :repository-policy verify` early and the complete
  Maven `verify` before finishing.
- Add a blocking sensor only for a stable, deterministic, actionable rule with
  low false-positive risk and focused tests.
- Turn recurring review findings into better guidance, a reliable sensor, or an
  explicit exception instead of repeatedly rediscovering them.

## Purpose and ownership

The coding harness combines feed-forward guidance with executable feedback so
contributors can discover the applicable rules, detect drift quickly, and
report evidence honestly. It does not replace product documents, module
READMEs, engineering standards, SQL criteria, or ADRs. The
[documentation map](../README.md#task-routing) selects those sources by task.

Stable repository contract shapes live in the repository-policy checker rather
than in editable prose alone. The documentation explains their intent; changing
both a guide and a template cannot silently remove an enforced obligation.
Runtime dependencies remain separate from harness dependencies.

## Feedback tiers

Use the lowest-cost tier that can disprove the current edit, then expand as the
change stabilizes. A later tier does not excuse skipping an applicable earlier
one, and a focused pass does not replace the required completion baseline.

| Tier | Purpose | When to use | Evidence |
|:--|:--|:--|:--|
| T0 — task routing | Select the authoritative guides, module ownership, ADRs, contracts, risks, and acceptance boundary before editing. | At task start and whenever scope changes. | The issue, plan, or completion report names the selected milestone, boundaries, and deliberate exclusions. |
| T1 — repository policy | Detect cheap repository, Markdown, ADR, milestone, and GitHub-template drift without Docker, PostgreSQL, or external services. | At task start for existing drift and repeatedly while changing controlled files. | `./mvnw -B -ntp -pl :repository-policy verify` passes. Maven may populate an empty local dependency cache. |
| T2 — full reactor | Compile, run static analysis and tests, and exercise the complete supported Maven build. | Before completion of every coding task and after final edits. | `./mvnw -B -ntp verify` passes with Docker available for Testcontainers-backed tests. |
| T3 — boundary-specific validation | Prove behavior that the aggregate build cannot establish by itself, such as supported migration upgrades, rendered Compose configuration, deployment/security configuration, recovery, or a manual operational path. | Whenever the task-routing row, issue acceptance criteria, module README, SQL criteria, or ADR requires it. | Record the exact command or inspection, environment, outcome, and residual limitation. |

The Makefile exposes `make repository-policy` and `make verify` as convenience
targets on systems with `make`; Maven Wrapper commands remain canonical.

Ergon does not currently claim a separate exhaustive architecture dependency
sensor. Architecture rules are enforced through module boundaries, tests,
review, and the engineering standards. The repository-policy check must not be
described as an architecture check. Adding a reliable architecture sensor is a
separate focused change with its own design and evidence.

## CI stages

The [CI workflow](../../.github/workflows/ci.yml) runs policy, formatting and
static analysis as independent parallel matrix jobs. After they succeed, one
runner executes compilation, unit tests, PostgreSQL tests and packaging in
separate steps. PostgreSQL tests still run after a unit failure if compilation
passed, but packaging requires all prior steps to pass. Docker concurrency,
assertions and timeouts are unchanged.

Run a stage locally with Python 3.11+ (standard library only):

```bash
python3 tools/ci/verify.py policy
python3 tools/ci/verify.py formatting
python3 tools/ci/verify.py static
python3 tools/ci/verify.py compile
python3 tools/ci/verify.py unit
python3 tools/ci/verify.py postgres
python3 tools/ci/verify.py package
```

Compilation prepares fresh flattened parent/domain
artifacts in the local Maven repository; direct test goals otherwise cannot
resolve the internal dependency across invocations. Later steps reuse compiled
classes instead of repeating Maven lifecycle phases. Packaging produces the
executable control-plane jar only after CI tests succeed. Do not clean targets
between these steps. Plain `./mvnw -B -ntp verify` remains the complete baseline;
the CI-only profiles do not change its selection.

The source inventory requires the existing three reactor modules and the
standard Surefire test-name patterns. All current container suites are
`control-plane/*IntegrationTest` classes; `ci-unit` excludes exactly that
pattern and `ci-postgres` includes it. Default test names are reserved for
actual suites, including composed-annotation tests. Update this explicit
convention and inventory together before introducing other suite mechanisms.
The runner removes only that lane's generated reports before execution and
rejects missing, unexpected, empty, failing or skipped suites. A zero Maven
exit code alone cannot establish complete coverage.

Each stage prints a bounded summary and writes the full Maven transcript to
`target/ci/<stage>.log`. Surefire redirects test output into per-suite files;
XML reports retain failure details. CI uploads diagnostics even on failure as
`ci-policy`, `ci-formatting`, `ci-static` and `ci-reactor`, retained seven days.
Use the workflow summary and Artifacts section; logs are not silenced with
`-q` or discarded. Keep secrets and production/customer data out of test logs.

The existing required status `build` is retained as an always-evaluated aggregate:
both the check matrix and reactor job must report success. Failed, cancelled,
skipped or missing prerequisites fail the gate. No branch-protection rename is
needed where `build` is already required; repository administrators must ensure
that status is actually required. The workflow cannot configure protection itself.
Regression checks: `python3 -m unittest discover -s tools/ci -v`.

## Repository policy

The tooling-only `tools/repository-policy` Maven module parses rendered
CommonMark, including GitHub-flavored tables, and reports deterministic,
sorted diagnostics. Each failure identifies the repository-relative path and
line, a stable rule ID, the violated rule, the allowed shape or likely fix, and
the canonical policy source.

The sensor validates:

- repository-local Markdown destinations, anchors, exact path casing, and
  containment inside the repository;
- a visible, non-empty TL;DR for qualifying non-ADR guides;
- ADR filenames, headings, metadata, required sections, lifecycle and
  supersession references, contiguous numbering, published high-water mark,
  and index coverage;
- milestone titles, numbers, short messages, matching detail sections, and the
  published high-water mark;
- issue-template front matter, milestone field, and canonical section order;
  and
- pull-request headings, prompts, verification commands, and review checklist.

ADR history is preserved rather than reformatted. Explicit legacy ranges admit
only the structures that were valid when those records were published; ADR
0042 and later use the current template. ADRs are exempt from the general
TL;DR rule because their fixed decision structure is checked independently.

Focused tests must prove that hidden comments, raw HTML, image alternative
text, code fences, moved prompts, changed commands, and synchronized weakening
of prose and templates cannot satisfy visible or canonical contracts. On
case-insensitive filesystems, link validation compares the written destination
with the repository's canonical path inventory.

The sensor deliberately does not validate external URL availability, factual
freshness, runtime behavior, database behavior, dependency vulnerabilities, or
license policy. Those concerns need different failure semantics and feedback
timing.

## Extending the harness

Before adding or strengthening a rule:

1. Identify the repeated defect, drift, or review cost and the canonical
   requirement it protects.
2. Decide whether clearer routing or guidance is sufficient. Do not automate a
   subjective preference merely because it can be counted.
3. Make a sensor blocking only when it is deterministic, platform-aware,
   repository-local, difficult to evade accidentally, and expected to remain
   low noise.
4. Produce a diagnostic that explains the violation, location, allowed shape,
   likely correction, and canonical policy.
5. Add positive, negative, and anti-evasion fixtures before enabling the rule
   on live files.
6. Fix existing violations explicitly. Do not introduce broad exclusions or
   weaken evidence to make the new sensor green.
7. Place tooling-only dependencies outside deployable modules and document the
   new command in task routing, local feedback, and CI as applicable.

A rule affecting compatibility, security, durability, data meaning, deployment
topology, or multiple applications still requires an ADR. A local low-risk
checker refinement may instead be explained by tests and the implementing
issue.

## Steering loop

For each recurring review finding, classify the response:

- improve feed-forward guidance when contributors could not discover the rule;
- add or strengthen a deterministic sensor when machines can decide the rule
  reliably;
- retain human review when correctness depends on context or product judgment;
  or
- record a narrow exception when the normal rule is deliberately unsuitable.

Review harness behavior periodically. Track failure categories, first-pass
success, duration, flaky or retried checks, recurring review findings, escaped
defects, and human rework. Treat these as evidence about where the harness
helps or obstructs; do not turn them into vanity targets. New sensors begin
focused or advisory and become blocking only after demonstrating useful,
stable signal.

CI runs policy and static checks before Docker-backed stages so inexpensive
drift fails early. The checkout does not persist GitHub credentials. The
aggregate gate retains complete verification rather than treating a partial
stage or uploaded artifact as success.

## Completion evidence

The [completion report](../../CONTRIBUTING.md#completion-report) separates
passed, failed, and not-run checks. For every unavailable T2 or applicable T3
check, name the blocking condition, evidence that did run, residual risk, and
where the missing check should run. A skipped Testcontainers test, an unstarted
Docker daemon, or an environment that cannot exercise an operational path is
not passing evidence.
