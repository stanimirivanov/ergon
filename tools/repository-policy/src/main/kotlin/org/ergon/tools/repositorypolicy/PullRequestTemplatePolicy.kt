package org.ergon.tools.repositorypolicy

internal object PullRequestTemplatePolicy {
    fun check(repository: Repository): List<Violation> =
        repository.document(PULL_REQUEST_TEMPLATE)?.let(::validate)
            ?: listOf(
                violation(
                    1,
                    "template.pull-request",
                    "pull request template is missing",
                    "restore `$PULL_REQUEST_TEMPLATE`",
                ),
            )

    private fun validate(template: MarkdownDocument): List<Violation> {
        val headings = template.headings.filter { it.level == 2 }
        return listOfNotNull(
            sectionViolation(headings),
            verificationViolation(template, headings),
            checklistViolation(template, headings),
        ) + promptViolations(template, headings)
    }

    private fun sectionViolation(headings: List<MarkdownHeading>): Violation? =
        if (headings.map { it.text } == PULL_REQUEST_HEADINGS && headings.none { it.containsRawHtml }) {
            null
        } else {
            violation(
                headings.firstOrNull()?.line ?: 1,
                "template.pull-request.sections",
                "pull request section order has drifted",
                "restore `${PULL_REQUEST_HEADINGS.joinToString("`, `")}` as visible level-two headings",
            )
        }

    private fun promptViolations(
        template: MarkdownDocument,
        headings: List<MarkdownHeading>,
    ): List<Violation> =
        PULL_REQUEST_MARKERS.mapNotNull { (section, marker) ->
            val heading = headings.firstOrNull { it.text == section }
            if (heading != null && marker in template.sectionText(heading)) {
                null
            } else {
                violation(
                    heading?.line ?: 1,
                    "template.pull-request.prompt",
                    "pull request section `$section` is missing visible prompt `$marker`",
                    "restore the prompt in its canonical section",
                )
            }
        }

    private fun verificationViolation(
        template: MarkdownDocument,
        headings: List<MarkdownHeading>,
    ): Violation? {
        val verification = headings.firstOrNull { it.text == "Verification" }
        val table = verification?.let(template::firstTableAfter)
        val commands = table?.rows?.drop(1)?.mapNotNull { row -> row.cells.firstOrNull()?.text }
        return if (commands == REQUIRED_VERIFICATION_COMMANDS) {
            null
        } else {
            violation(
                table?.line ?: verification?.line ?: 1,
                "template.pull-request.verification",
                "pull request verification command rows have drifted",
                "restore the two canonical commands in order as exact inline-code values",
            )
        }
    }

    private fun checklistViolation(
        template: MarkdownDocument,
        headings: List<MarkdownHeading>,
    ): Violation? {
        val review = headings.firstOrNull { it.text == "Review checklist" }
        val checklist = review?.let { uncheckedChecklist(template, it) }.orEmpty()
        return if (checklist == PULL_REQUEST_CHECKLIST) {
            null
        } else {
            violation(
                review?.line ?: 1,
                "template.pull-request.checklist",
                "pull request review checklist has drifted",
                "restore the ordered unchecked checklist from the canonical review contract",
            )
        }
    }

    private fun uncheckedChecklist(
        document: MarkdownDocument,
        heading: MarkdownHeading,
    ): List<String> {
        val end =
            document.headings
                .firstOrNull { it.line > heading.line && it.level <= heading.level }
                ?.line ?: Int.MAX_VALUE
        return document.body
            .lineSequence()
            .drop(heading.line)
            .take(end - heading.line - 1)
            .mapIndexedNotNull { offset, line -> visibleChecklistItem(document, heading, offset, line) }
            .toList()
    }

    private fun visibleChecklistItem(
        document: MarkdownDocument,
        heading: MarkdownHeading,
        offset: Int,
        line: String,
    ): String? {
        val match = CHECKBOX.matchEntire(line.trim())
        val value = match?.groupValues?.get(1)
        val sourceLine = heading.line + offset + 1
        return value?.takeIf { document.visibleText(sourceLine, sourceLine + 1).contains(value) }
    }

    private fun violation(
        line: Int,
        rule: String,
        message: String,
        correction: String,
    ) = Violation(PULL_REQUEST_TEMPLATE, line, rule, message, correction, PULL_REQUEST_POLICY)

    private val PULL_REQUEST_HEADINGS =
        listOf(
            "Issue and milestone",
            "Problem and resulting behavior",
            "Scope and exclusions",
            "Design and compatibility decisions",
            "Verification",
            "Migration, rollout, and rollback",
            "Security and operations",
            "Review checklist",
        )
    private val PULL_REQUEST_MARKERS =
        listOf(
            "Issue and milestone" to "Closes #",
            "Issue and milestone" to "Milestone: MNN - Outcome",
            "Design and compatibility decisions" to "ADRs:",
            "Design and compatibility decisions" to "Assumptions:",
            "Design and compatibility decisions" to "Known limitations:",
            "Verification" to "Checks not run, blocking conditions, and residual risk:",
        )
    private val REQUIRED_VERIFICATION_COMMANDS =
        listOf(
            "./mvnw -B -ntp -pl :repository-policy verify",
            "./mvnw -B -ntp verify",
        )
    private val PULL_REQUEST_CHECKLIST =
        listOf(
            "One coherent capability; unrelated changes are excluded.",
            "Public and persisted contracts have a compatibility plan.",
            "Success and relevant negative paths are verified.",
            "Tenant, authorization, concurrency, and idempotency risks are covered.",
            "Documentation, ADRs, and module READMEs are current.",
            "Migrations pass empty and supported-upgrade paths where applicable.",
            "Repository policy passes.",
            "No secrets, production data, generated noise, or hidden skipped checks.",
        )
    private val CHECKBOX = Regex("^- \\[ \\] (.+)$")
}
