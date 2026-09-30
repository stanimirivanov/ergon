# Repository policy tool

## TL;DR

This tooling-only Maven module validates Ergon's local Markdown navigation,
documentation summaries, decision history, milestone roadmap, and GitHub review
templates. It has no dependency edge to deployable product modules.

Run the focused deterministic check from the repository root:

```powershell
.\mvnw.cmd -B -ntp -pl :repository-policy verify
```

```bash
./mvnw -B -ntp -pl :repository-policy verify
```

The module's unit tests exercise policy failures against isolated fixtures. Its
`verify` execution then checks the current repository and reports all violations
in stable path and line order. The complete repository gate remains the root
Maven `verify` command documented in [CONTRIBUTING.md](../../CONTRIBUTING.md).

## Ownership

Repository-policy dependencies remain here so Markdown parsing and harness
evolution do not enter the control-plane or domain-kernel dependency graphs.
Add a rule only with canonical guidance, focused positive and negative tests,
an actionable diagnostic, and a clear feedback-tier owner.
