package org.ergon.tools.repositorypolicy

internal const val ADR_INDEX = "docs/decisions/README.md"
internal const val ADR_TEMPLATE = "docs/decisions/0000-template.md"
internal const val ADR_POLICY = "docs/decisions/README.md"
internal const val CURRENT_FORMAT_FIRST_ADR = 42
internal const val PREDECESSOR_LAST_ADR = 5
internal const val EARLY_TEMPLATE_LAST_ADR = 28
internal const val TRANSITIONAL_TEMPLATE_LAST_ADR = 31
internal const val RECENT_LEGACY_LAST_ADR = 41
internal const val ADR_NUMBER_WIDTH = 4

internal object AdrPolicy {
    const val LATEST_PUBLISHED_ADR = 45
    private val filename = Regex("docs/decisions/(\\d{4})-([a-z0-9]+(?:-[a-z0-9]+)*)\\.md")

    fun check(repository: Repository): List<Violation> {
        val (records, discoveryViolations) = discover(repository)
        return discoveryViolations +
            AdrRecordPolicy.check(records) +
            validateSequence(records) +
            AdrIndexPolicy.check(repository, records) +
            AdrSupersessionPolicy.check(records) +
            AdrTemplatePolicy.check(repository.document(ADR_TEMPLATE))
    }

    internal fun validateSequence(
        records: List<AdrRecord>,
        latestPublished: Int = LATEST_PUBLISHED_ADR,
    ): List<Violation> {
        val ordered = records.sortedBy { it.number }
        val duplicateViolations =
            ordered
                .groupingBy { it.number }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .map { number -> duplicateNumber(ordered.first { it.number == number }) }
        val actual = ordered.map { it.number }.distinct()
        val expected = (1..latestPublished).toList()
        val sequenceViolation = sequenceViolation(actual, expected)
        return duplicateViolations + listOfNotNull(sequenceViolation)
    }

    private fun discover(repository: Repository): Pair<List<AdrRecord>, List<Violation>> {
        val records = mutableListOf<AdrRecord>()
        val violations = mutableListOf<Violation>()
        repository.documents.values
            .filter { it.relativePath.startsWith("docs/decisions/") }
            .filter { it.relativePath != ADR_INDEX && it.relativePath != ADR_TEMPLATE }
            .forEach { document ->
                val match = filename.matchEntire(document.relativePath)
                if (match == null) {
                    violations += invalidFilename(document.relativePath)
                } else {
                    records += readRecord(document, match.groupValues[1].toInt())
                }
            }
        return records to violations
    }

    private fun readRecord(
        document: MarkdownDocument,
        number: Int,
    ): AdrRecord {
        val title = document.headings.firstOrNull { it.level == 1 }
        val firstSection = document.headings.firstOrNull { it.level == 2 }?.line ?: Int.MAX_VALUE
        val metadata = mutableMapOf<String, MetadataValue>()
        document.body.lineSequence().forEachIndexed { index, source ->
            val line = index + 1
            val match = METADATA.matchEntire(source.trim())
            val visibleMetadata =
                match != null &&
                    document.visibleText(line, line + 1).contains(match.groupValues[1])
            if (line < firstSection && visibleMetadata) {
                metadata[match.groupValues[1]] = MetadataValue(match.groupValues[2].trim(), line)
            }
        }
        return AdrRecord(number, document, title, metadata)
    }

    private fun sequenceViolation(
        actual: List<Int>,
        expected: List<Int>,
    ): Violation? {
        if (actual == expected) return null
        val mismatch =
            (0 until maxOf(actual.size, expected.size))
                .firstOrNull { index -> actual.getOrNull(index) != expected.getOrNull(index) } ?: 0
        val expectedText = expected.getOrNull(mismatch)?.padded() ?: "no additional record"
        val actualText = actual.getOrNull(mismatch)?.padded() ?: "end of sequence"
        return adrViolation(
            ADR_INDEX,
            1,
            "adr.sequence",
            "published ADR sequence expected $expectedText, found $actualText",
            "restore deleted history, fill the gap, or advance the high-water mark only with the next indexed ADR",
        )
    }

    private fun invalidFilename(path: String) =
        adrViolation(
            path,
            1,
            "adr.filename",
            "ADR filename must use a four-digit number and lowercase kebab-case description",
            "rename the record to `NNNN-short-decision-title.md` and update its index entry",
        )

    private fun duplicateNumber(record: AdrRecord) =
        adrViolation(
            record.document.relativePath,
            1,
            "adr.sequence",
            "ADR number ${record.number.padded()} is used more than once",
            "retain exactly one record for each published ADR number",
        )

    private val METADATA = Regex("^- ([A-Za-z ]+):\\s*(.*)$")
}

internal data class AdrRecord(
    val number: Int,
    val document: MarkdownDocument,
    val title: MarkdownHeading?,
    val metadata: Map<String, MetadataValue>,
) {
    fun references(key: String): Set<Int> =
        metadata[key]
            ?.value
            ?.let { value -> ADR_REFERENCE.findAll(value).map { it.groupValues[1].toInt() }.toSet() }
            .orEmpty()
}

internal data class MetadataValue(
    val value: String,
    val line: Int,
)

internal fun adrViolation(
    path: String,
    line: Int,
    rule: String,
    message: String,
    correction: String,
) = Violation(path, line, rule, message, correction, ADR_POLICY)

internal fun Int.padded(): String = toString().padStart(ADR_NUMBER_WIDTH, '0')

private val ADR_REFERENCE = Regex("(?i)(?:ADR\\s*)?(\\d{4})")
