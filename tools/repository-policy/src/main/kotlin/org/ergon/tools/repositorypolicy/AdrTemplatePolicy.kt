package org.ergon.tools.repositorypolicy

internal object AdrTemplatePolicy {
    fun check(template: MarkdownDocument?): List<Violation> =
        template?.let(::validate)
            ?: listOf(
                adrViolation(
                    ADR_TEMPLATE,
                    1,
                    "adr.template",
                    "ADR template is missing",
                    "restore the current ADR template",
                ),
            )

    private fun validate(template: MarkdownDocument): List<Violation> =
        listOfNotNull(
            validateTitle(template),
            validateSections(template),
        ) + validateMetadata(template)

    private fun validateTitle(template: MarkdownDocument): Violation? {
        val title = template.headings.firstOrNull { it.level == 1 }
        return if (title?.text == "ADR NNNN: Decision title" && !title.containsRawHtml) {
            null
        } else {
            adrViolation(
                ADR_TEMPLATE,
                title?.line ?: 1,
                "adr.template",
                "ADR template title has drifted",
                "restore `# ADR NNNN: Decision title` as visible Markdown",
            )
        }
    }

    private fun validateSections(template: MarkdownDocument): Violation? {
        val sections = template.headings.filter { it.level == 2 }.map { it.text }
        return if (sections == AdrRecordPolicy.CURRENT_SECTIONS) {
            null
        } else {
            adrViolation(
                ADR_TEMPLATE,
                1,
                "adr.template",
                "ADR template required section order has drifted",
                "restore `${AdrRecordPolicy.CURRENT_SECTIONS.joinToString("`, `")}` in order",
            )
        }
    }

    private fun validateMetadata(template: MarkdownDocument): List<Violation> =
        AdrRecordPolicy.REQUIRED_CURRENT_METADATA
            .filterNot { key ->
                template.body.lineSequence().any { line -> line.trimStart().startsWith("- $key:") }
            }.map { key ->
                adrViolation(
                    ADR_TEMPLATE,
                    1,
                    "adr.template",
                    "ADR template is missing `$key` metadata",
                    "restore the complete visible metadata block",
                )
            }
}
