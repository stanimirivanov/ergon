package org.ergon.tools.repositorypolicy

internal object TldrPolicy {
    private const val POLICY = "CONTRIBUTING.md#documentation"
    private const val LONG_DOCUMENT_WORDS = 800
    private const val MANY_SECTIONS = 5
    private const val HEADING_LEVEL_TWO = 2
    private const val HTML_COMMENT_END_LENGTH = 3
    private const val HTML_COMMENT_START_LENGTH = 4

    fun check(repository: Repository): List<Violation> =
        repository.documents.values
            .filter(::requiresTldr)
            .mapNotNull(::validate)

    internal fun requiresTldr(document: MarkdownDocument): Boolean {
        if (isExempt(document.relativePath)) return false
        return document.wordCount >= LONG_DOCUMENT_WORDS ||
            document.headings.count { it.level == HEADING_LEVEL_TWO } > MANY_SECTIONS ||
            isLongFormGuide(document.relativePath)
    }

    private fun validate(document: MarkdownDocument): Violation? {
        val title = document.headings.firstOrNull { it.level == 1 }
        val firstSection = document.headings.firstOrNull { it.level == HEADING_LEVEL_TWO }
        val valid =
            listOf(
                correctHeading(title, firstSection),
                noContentBefore(document, title, firstSection),
                hasSummary(document, firstSection),
            ).all { it }

        return if (valid) null else summaryViolation(document, title, firstSection)
    }

    private fun correctHeading(
        title: MarkdownHeading?,
        summary: MarkdownHeading?,
    ): Boolean =
        title != null &&
            summary != null &&
            title.line < summary.line &&
            summary.text == "TL;DR" &&
            !summary.containsRawHtml

    private fun noContentBefore(
        document: MarkdownDocument,
        title: MarkdownHeading?,
        summary: MarkdownHeading?,
    ): Boolean = title != null && summary != null && noContentBeforeSummary(document, title.line, summary.line)

    private fun hasSummary(
        document: MarkdownDocument,
        summary: MarkdownHeading?,
    ): Boolean {
        if (summary == null) return false
        val nextSectionLine =
            document.headings
                .firstOrNull { it.line > summary.line && it.level <= HEADING_LEVEL_TWO }
                ?.line ?: Int.MAX_VALUE
        return document.visibleText(summary.line + 1, nextSectionLine).any(Char::isLetterOrDigit)
    }

    private fun summaryViolation(
        document: MarkdownDocument,
        title: MarkdownHeading?,
        summary: MarkdownHeading?,
    ) = Violation(
        path = document.relativePath,
        line = summary?.line ?: title?.line?.plus(1) ?: 1,
        rule = "markdown.tldr",
        message = "long-form guide must begin its level-two sections with a visible, non-empty `TL;DR`",
        correction =
            "place `## TL;DR` and rendered summary prose immediately after the title and " +
                "permitted status metadata",
        policy = POLICY,
    )

    private fun noContentBeforeSummary(
        document: MarkdownDocument,
        titleLine: Int,
        summaryLine: Int,
    ): Boolean {
        var inComment = false
        return document.body
            .lineSequence()
            .drop(titleLine)
            .take(summaryLine - titleLine - 1)
            .map { line -> withoutComments(line, inComment).also { inComment = it.second }.first }
            .all { line -> line.isBlank() || isStatusMetadata(line.trim()) }
    }

    private fun withoutComments(
        line: String,
        startedInsideComment: Boolean,
    ): Pair<String, Boolean> {
        var remaining = line
        var inComment = startedInsideComment
        val visible = StringBuilder()
        while (true) {
            if (inComment) {
                val end = remaining.indexOf("-->")
                if (end < 0) return visible.toString() to true
                remaining = remaining.substring(end + HTML_COMMENT_END_LENGTH)
                inComment = false
                continue
            }
            val start = remaining.indexOf("<!--")
            if (start < 0) {
                visible.append(remaining)
                return visible.toString() to false
            }
            visible.append(remaining.substring(0, start))
            remaining = remaining.substring(start + HTML_COMMENT_START_LENGTH)
            inComment = true
        }
    }

    private fun isStatusMetadata(line: String): Boolean =
        line.startsWith("Status:") || line.startsWith("**Status:**") || line.startsWith("- Status:")
}

private fun isExempt(path: String): Boolean =
    path.startsWith("docs/decisions/") ||
        path.startsWith(".github/ISSUE_TEMPLATE/") ||
        path == ".github/ISSUE_TEMPLATE.md" ||
        path.startsWith(".github/PULL_REQUEST_TEMPLATE/") ||
        path == ".github/PULL_REQUEST_TEMPLATE.md"

private fun isLongFormGuide(path: String): Boolean {
    val normalized = path.lowercase()
    return normalized.contains("security") ||
        normalized == "docs/architecture.md" ||
        normalized.startsWith("docs/architecture/") ||
        normalized.startsWith("docs/operations/") ||
        normalized.contains("migration") ||
        normalized.contains("operational") ||
        normalized.contains("end-to-end")
}
