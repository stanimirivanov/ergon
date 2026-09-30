package org.ergon.tools.repositorypolicy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class TemplatePolicyTest {
    @Test
    fun `canonical issue and pull request contracts pass`() {
        assertThat(TemplatePolicy.check(repository())).isEmpty()
    }

    @Test
    fun `changing policy and issue template together cannot weaken stable contract`() {
        val documents = documents().toMutableMap()
        documents["CONTRIBUTING.md"] =
            documents.getValue("CONTRIBUTING.md").replace(ACCEPTANCE_SECTION, "")
        documents[".github/ISSUE_TEMPLATE/implementation.md"] =
            documents.getValue(".github/ISSUE_TEMPLATE/implementation.md").replace(ACCEPTANCE_SECTION, "")

        assertThat(TemplatePolicy.check(repository(documents)).map { it.rule })
            .contains("template.issue.body", "template.issue.policy")
    }

    @Test
    fun `moved prompt and modified command fail pull request policy`() {
        val documents = documents().toMutableMap()
        documents[".github/PULL_REQUEST_TEMPLATE.md"] =
            documents
                .getValue(".github/PULL_REQUEST_TEMPLATE.md")
                .replace("- ADRs:\n", "")
                .replace("./mvnw -B -ntp verify", "./mvnw verify")

        assertThat(TemplatePolicy.check(repository(documents)).map { it.rule })
            .contains("template.pull-request.prompt", "template.pull-request.verification")
    }

    @Test
    fun `commented checklist cannot satisfy visible review contract`() {
        val documents = documents().toMutableMap()
        documents[".github/PULL_REQUEST_TEMPLATE.md"] =
            documents
                .getValue(".github/PULL_REQUEST_TEMPLATE.md")
                .replace(
                    "- [ ] Repository policy passes.",
                    "<!-- - [ ] Repository policy passes. -->",
                )

        assertThat(TemplatePolicy.check(repository(documents)).map { it.rule })
            .contains("template.pull-request.checklist")
    }

    private fun repository(documents: Map<String, String> = documents()): Repository =
        Repository.fixture(
            Path.of("."),
            documents,
        )

    private fun documents(): Map<String, String> =
        mapOf(
            "CONTRIBUTING.md" to
                """# Contributing

## Required issue structure

```markdown
${ISSUE_BODY}
```

## Pull request description

Policy.
""",
            ".github/ISSUE_TEMPLATE/implementation.md" to
                """---
name: Implementation task
about: Propose one coherent, reviewable capability
title: ""
labels: ""
assignees: ""
---

${ISSUE_BODY}
""",
            ".github/PULL_REQUEST_TEMPLATE.md" to PULL_REQUEST_TEMPLATE_BODY,
        )

    private companion object {
        const val ISSUE_BODY =
            """**Milestone:** MNN - Outcome

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

- Explicit exclusions and deferred work."""

        const val PULL_REQUEST_TEMPLATE_BODY =
            """## Issue and milestone

- Closes #
- Milestone: MNN - Outcome

## Problem and resulting behavior

## Scope and exclusions

## Design and compatibility decisions

- ADRs:
- Assumptions:
- Known limitations:

## Verification

| Command or check | Result | Evidence or reason not run |
|:--|:--|:--|
| `./mvnw -B -ntp -pl :repository-policy verify` | | |
| `./mvnw -B -ntp verify` | | |

Checks not run, blocking conditions, and residual risk:

## Migration, rollout, and rollback

## Security and operations

## Review checklist

- [ ] One coherent capability; unrelated changes are excluded.
- [ ] Public and persisted contracts have a compatibility plan.
- [ ] Success and relevant negative paths are verified.
- [ ] Tenant, authorization, concurrency, and idempotency risks are covered.
- [ ] Documentation, ADRs, and module READMEs are current.
- [ ] Migrations pass empty and supported-upgrade paths where applicable.
- [ ] Repository policy passes.
- [ ] No secrets, production data, generated noise, or hidden skipped checks.
"""

        const val ACCEPTANCE_SECTION =
            """## Acceptance criteria

- [ ] Observable behavior and verification evidence.
- [ ] Relevant negative and failure behavior.
- [ ] Documentation and operational effects.

"""
    }
}
