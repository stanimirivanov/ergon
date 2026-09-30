package org.ergon.tools.repositorypolicy

private const val FRONT_MATTER_START_LENGTH = 4
private const val FRONT_MATTER_DELIMITER_LENGTH = 5

internal object IssueTemplatePolicy {
    fun check(repository: Repository): List<Violation> =
        repository.document(ISSUE_TEMPLATE)?.let { template -> validate(repository, template) }
            ?: listOf(
                issueViolation(
                    1,
                    "template.issue",
                    "implementation issue template is missing",
                    "restore `$ISSUE_TEMPLATE`",
                ),
            )

    private fun validate(
        repository: Repository,
        template: MarkdownDocument,
    ): List<Violation> {
        val (frontMatter, body) = splitFrontMatter(template.body)
        return listOfNotNull(
            frontMatterViolation(frontMatter),
            bodyViolation(template, body),
            policyViolation(repository),
        )
    }

    private fun frontMatterViolation(frontMatter: String): Violation? =
        if (frontMatter == CANONICAL_ISSUE_FRONT_MATTER) {
            null
        } else {
            issueViolation(
                1,
                "template.issue.front-matter",
                "implementation issue front matter has drifted",
                "restore the canonical name, description, and empty title, label, and assignee defaults",
            )
        }

    private fun bodyViolation(
        template: MarkdownDocument,
        body: String,
    ): Violation? =
        if (normalize(body) == normalize(CANONICAL_ISSUE_BODY)) {
            null
        } else {
            issueViolation(
                template.headings.firstOrNull()?.line ?: 1,
                "template.issue.body",
                "implementation issue body has drifted from the stable review contract",
                "restore the canonical milestone field, section order, and prompts from `$ISSUE_POLICY`",
            )
        }

    private fun policyViolation(repository: Repository): Violation? {
        val contributing = repository.document(CONTRIBUTING)
        val issueSection =
            contributing?.headings?.firstOrNull {
                it.level == 2 && it.text == "Required issue structure"
            }
        val block = canonicalBlock(contributing, issueSection)
        return if (block != null && normalize(block.body) == normalize(CANONICAL_ISSUE_BODY)) {
            null
        } else {
            Violation(
                CONTRIBUTING,
                block?.line ?: issueSection?.line ?: 1,
                "template.issue.policy",
                "canonical issue example has drifted from the stable checker contract",
                "restore the `markdown` block below `## Required issue structure`",
                ISSUE_POLICY,
            )
        }
    }

    private fun canonicalBlock(
        contributing: MarkdownDocument?,
        section: MarkdownHeading?,
    ): MarkdownCodeBlock? {
        if (contributing == null || section == null) return null
        val end =
            contributing.headings
                .firstOrNull { it.line > section.line && it.level <= 2 }
                ?.line ?: Int.MAX_VALUE
        return contributing.codeBlocks.singleOrNull {
            it.line in (section.line + 1) until end && it.info.trim() == "markdown"
        }
    }

    private fun splitFrontMatter(body: String): Pair<String, String> {
        val normalized = normalize(body)
        val end = normalized.indexOf("\n---\n", startIndex = FRONT_MATTER_START_LENGTH)
        return when {
            !normalized.startsWith("---\n") -> {
                "" to normalized
            }

            end < 0 -> {
                "" to normalized
            }

            else -> {
                normalized.substring(FRONT_MATTER_START_LENGTH, end).trim() to
                    normalized.substring(end + FRONT_MATTER_DELIMITER_LENGTH).trim()
            }
        }
    }

    private fun normalize(value: String): String = value.replace("\r\n", "\n").trim()

    private fun issueViolation(
        line: Int,
        rule: String,
        message: String,
        correction: String,
    ) = Violation(ISSUE_TEMPLATE, line, rule, message, correction, ISSUE_POLICY)

    private const val CANONICAL_ISSUE_FRONT_MATTER =
        """name: Implementation task
about: Propose one coherent, reviewable capability
title: ""
labels: ""
assignees: """""
    private const val CANONICAL_ISSUE_BODY =
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
}
