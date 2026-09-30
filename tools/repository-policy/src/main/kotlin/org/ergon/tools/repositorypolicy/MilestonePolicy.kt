package org.ergon.tools.repositorypolicy

private const val MILESTONE_ROADMAP = "docs/roadmap/milestones.md"
private const val MILESTONE_POLICY = "$MILESTONE_ROADMAP#planning-rules"

internal object MilestonePolicy {
    const val LATEST_PUBLISHED_MILESTONE = 8
    private const val HEADING_LEVEL_TWO = 2
    private val titlePattern = Regex("M(\\d{2}) - (.+)")

    fun check(repository: Repository): List<Violation> =
        repository.document(MILESTONE_ROADMAP)?.let(::checkDocument)
            ?: listOf(
                MilestoneViolations.create(
                    1,
                    "milestone.roadmap",
                    "milestone roadmap is missing",
                    "restore `$MILESTONE_ROADMAP`",
                ),
            )

    private fun checkDocument(document: MarkdownDocument): List<Violation> {
        val indexHeading =
            document.headings.firstOrNull {
                it.level == HEADING_LEVEL_TWO && it.text == "Milestone index"
            }
        val table = indexHeading?.let(document::firstTableAfter)
        return when {
            indexHeading == null -> {
                listOf(
                    MilestoneViolations.create(
                        1,
                        "milestone.index",
                        "roadmap has no visible `Milestone index` section",
                        "restore `## Milestone index` and its rendered table",
                    ),
                )
            }

            table == null -> {
                listOf(
                    MilestoneViolations.create(
                        indexHeading.line,
                        "milestone.index",
                        "milestone index contains no rendered table",
                        "restore one table row for every published milestone",
                    ),
                )
            }

            else -> {
                validateRoadmap(document, table)
            }
        }
    }

    private fun validateRoadmap(
        document: MarkdownDocument,
        table: MarkdownTable,
    ): List<Violation> {
        val (milestones, indexViolations) = readMilestones(table)
        return indexViolations + validateSequence(milestones) + validateBoundaries(document, milestones)
    }

    private fun readMilestones(table: MarkdownTable): Pair<List<Milestone>, List<Violation>> {
        val violations = mutableListOf<Violation>()
        val milestones = mutableListOf<Milestone>()
        table.rows.drop(1).forEach { row ->
            val titleCell = row.cells.getOrNull(0)
            val messageCell = row.cells.getOrNull(1)
            val title = titleCell?.text.orEmpty()
            val message = messageCell?.text.orEmpty()
            val match = titlePattern.matchEntire(title)
            if (match == null || titleCell?.containsRawHtml == true) {
                violations += MilestoneViolations.invalidTitle(row.line, title)
            } else {
                milestones += Milestone(match.groupValues[1].toInt(), title, message, row.line)
                if (message.none(Char::isLetterOrDigit) || messageCell?.containsRawHtml == true) {
                    violations += MilestoneViolations.invalidMessage(row.line, title)
                }
            }
        }
        return milestones to violations
    }

    internal fun validateSequence(
        milestones: List<Milestone>,
        latestPublished: Int = LATEST_PUBLISHED_MILESTONE,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val seen = mutableSetOf<Int>()
        milestones.forEachIndexed { index, milestone ->
            val expected = index + 1
            if (!seen.add(milestone.number)) {
                violations += MilestoneViolations.duplicateNumber(milestone)
            }
            if (milestone.number != expected) {
                violations += MilestoneViolations.unexpectedNumber(milestone, expected)
            }
        }
        val latest = milestones.lastOrNull()?.number ?: 0
        if (latest != latestPublished) {
            violations += MilestoneViolations.highWater(milestones.lastOrNull(), latest, latestPublished)
        }
        return violations
    }

    private fun validateBoundaries(
        document: MarkdownDocument,
        milestones: List<Milestone>,
    ): List<Violation> {
        val boundaries = readBoundaries(document)
        val byNumber = boundaries.groupBy { it.number }
        val indexed = milestones.flatMap { milestone -> validateBoundary(document, milestone, byNumber) }
        val stale =
            boundaries
                .filter { boundary -> milestones.none { it.number == boundary.number } }
                .map(MilestoneViolations::staleBoundary)
        return indexed + stale
    }

    private fun readBoundaries(document: MarkdownDocument): List<MilestoneBoundary> =
        document.headings.mapNotNull { heading ->
            val match = titlePattern.matchEntire(heading.text)
            if (heading.level == HEADING_LEVEL_TWO && match != null) {
                MilestoneBoundary(match.groupValues[1].toInt(), heading.text, heading)
            } else {
                null
            }
        }

    private fun validateBoundary(
        document: MarkdownDocument,
        milestone: Milestone,
        boundaries: Map<Int, List<MilestoneBoundary>>,
    ): List<Violation> {
        val matches = boundaries[milestone.number].orEmpty()
        return when {
            matches.isEmpty() -> {
                listOf(MilestoneViolations.missingBoundary(milestone))
            }

            matches.size > 1 -> {
                listOf(MilestoneViolations.duplicateBoundary(milestone, matches[1]))
            }

            !matches.single().matches(milestone) -> {
                listOf(MilestoneViolations.mismatchedBoundary(milestone, matches.single()))
            }

            else -> {
                boundaryMessage(document, milestone, matches.single()).let(::listOfNotNull)
            }
        }
    }

    private fun boundaryMessage(
        document: MarkdownDocument,
        milestone: Milestone,
        boundary: MilestoneBoundary,
    ): Violation? {
        val message =
            MESSAGE
                .find(document.sectionText(boundary.heading))
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return when {
            message.none(Char::isLetterOrDigit) -> {
                MilestoneViolations.create(
                    boundary.heading.line,
                    "milestone.message",
                    "${milestone.title} boundary has no visible `Message:` outcome",
                    "add `**Message:**` followed by the indexed short outcome message",
                )
            }

            normalize(message) != normalize(milestone.message) -> {
                MilestoneViolations.create(
                    boundary.heading.line,
                    "milestone.message",
                    "${milestone.title} boundary message differs from its indexed short message",
                    "use the same short outcome message in the index and boundary section",
                )
            }

            else -> {
                null
            }
        }
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private val MESSAGE =
        Regex(
            "Message:\\s+(.+?)(?=Delivered outcomes|Candidate PR-sized outcomes|" +
                "Candidate outcomes|Completion means|$)",
        )
}

private object MilestoneViolations {
    fun invalidTitle(
        line: Int,
        title: String,
    ) = create(
        line,
        "milestone.title",
        "milestone index title `$title` does not use `MNN - Outcome`",
        "use a contiguous two-digit milestone number and short outcome title",
    )

    fun invalidMessage(
        line: Int,
        title: String,
    ) = create(
        line,
        "milestone.message",
        "$title has no visible short outcome message",
        "add a concise message describing what becomes true at completion",
    )

    fun duplicateNumber(milestone: Milestone) =
        create(
            milestone.line,
            "milestone.sequence",
            "${milestone.title} repeats a published milestone number",
            "retain exactly one ordered row for every published milestone",
        )

    fun unexpectedNumber(
        milestone: Milestone,
        expected: Int,
    ) = create(
        milestone.line,
        "milestone.sequence",
        "milestone sequence expected M${expected.twoDigits()}, found M${milestone.number.twoDigits()}",
        "start at M01 and keep published milestone numbers contiguous and ordered",
    )

    fun highWater(
        last: Milestone?,
        latest: Int,
        expected: Int,
    ) = create(
        last?.line ?: 1,
        "milestone.sequence",
        "latest milestone is M${latest.twoDigits()}; repository policy records M${expected.twoDigits()}",
        "restore deleted history or advance the high-water mark only while publishing the next milestone",
    )

    fun missingBoundary(milestone: Milestone) =
        create(
            milestone.line,
            "milestone.boundaries",
            "${milestone.title} has no boundary section",
            "add one `## ${milestone.title}` section describing delivered and candidate outcomes",
        )

    fun duplicateBoundary(
        milestone: Milestone,
        duplicate: MilestoneBoundary,
    ) = create(
        duplicate.heading.line,
        "milestone.boundaries",
        "${milestone.title} has more than one boundary section",
        "retain exactly one boundary section",
    )

    fun mismatchedBoundary(
        milestone: Milestone,
        boundary: MilestoneBoundary,
    ) = create(
        boundary.heading.line,
        "milestone.boundaries",
        "milestone boundary title differs from indexed title `${milestone.title}`",
        "copy the exact plain-Markdown indexed title into the level-two heading",
    )

    fun staleBoundary(boundary: MilestoneBoundary) =
        create(
            boundary.heading.line,
            "milestone.boundaries",
            "${boundary.title} has no milestone index row",
            "add the published milestone to the index or remove an unpublished stale boundary",
        )

    fun create(
        line: Int,
        rule: String,
        message: String,
        correction: String,
    ) = Violation(MILESTONE_ROADMAP, line, rule, message, correction, MILESTONE_POLICY)
}

internal data class Milestone(
    val number: Int,
    val title: String,
    val message: String,
    val line: Int,
)

private data class MilestoneBoundary(
    val number: Int,
    val title: String,
    val heading: MarkdownHeading,
) {
    fun matches(milestone: Milestone): Boolean = title == milestone.title && !heading.containsRawHtml
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
