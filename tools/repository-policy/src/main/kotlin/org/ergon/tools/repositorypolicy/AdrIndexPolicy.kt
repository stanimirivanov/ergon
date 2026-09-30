package org.ergon.tools.repositorypolicy

internal object AdrIndexPolicy {
    fun check(
        repository: Repository,
        records: List<AdrRecord>,
    ): List<Violation> =
        repository.document(ADR_INDEX)?.let { index -> validateDocument(index, records) }
            ?: listOf(
                adrViolation(
                    ADR_INDEX,
                    1,
                    "adr.index",
                    "ADR index is missing",
                    "restore the ADR index",
                ),
            )

    private fun validateDocument(
        index: MarkdownDocument,
        records: List<AdrRecord>,
    ): List<Violation> {
        val heading = index.headings.firstOrNull { it.level == 2 && it.text == "Index" }
        val table = heading?.let(index::firstTableAfter)
        return when {
            heading == null -> listOf(AdrIndexViolations.missingSection())
            table == null -> listOf(AdrIndexViolations.missingTable(heading.line))
            else -> validateEntries(readEntries(table), records)
        }
    }

    private fun readEntries(table: MarkdownTable): IndexedAdrs {
        val entries = mutableListOf<AdrIndexEntry>()
        val violations = mutableListOf<Violation>()
        table.rows.drop(1).forEach { row ->
            val number =
                row.cells
                    .getOrNull(0)
                    ?.text
                    .orEmpty()
                    .toIntOrNull()
            val destination =
                row.cells
                    .getOrNull(1)
                    ?.destinations
                    ?.singleOrNull()
            if (number == null || destination == null) {
                violations += AdrIndexViolations.invalidRow(row.line)
            } else {
                entries += AdrIndexEntry(number, destination, row.line)
            }
        }
        return IndexedAdrs(entries, violations)
    }

    private fun validateEntries(
        indexed: IndexedAdrs,
        records: List<AdrRecord>,
    ): List<Violation> {
        val byNumber = indexed.entries.groupBy { it.number }
        val recordViolations = records.flatMap { record -> validateRecord(record, byNumber[record.number].orEmpty()) }
        val stale =
            indexed.entries
                .filter { entry -> records.none { it.number == entry.number } }
                .map(AdrIndexViolations::staleEntry)
        return indexed.violations + recordViolations + stale
    }

    private fun validateRecord(
        record: AdrRecord,
        matches: List<AdrIndexEntry>,
    ): List<Violation> =
        when {
            matches.isEmpty() -> {
                listOf(AdrIndexViolations.missingEntry(record))
            }

            matches.size > 1 -> {
                listOf(AdrIndexViolations.duplicateEntry(record, matches[1]))
            }

            matches.single().destination != record.filename() -> {
                listOf(AdrIndexViolations.wrongDestination(record, matches.single()))
            }

            else -> {
                emptyList()
            }
        }
}

private object AdrIndexViolations {
    fun missingSection() =
        adrViolation(
            ADR_INDEX,
            1,
            "adr.index",
            "ADR index has no visible `Index` section",
            "restore `## Index` and its table",
        )

    fun missingTable(line: Int) =
        adrViolation(
            ADR_INDEX,
            line,
            "adr.index",
            "ADR index contains no rendered table",
            "restore the indexed ADR table",
        )

    fun invalidRow(line: Int) =
        adrViolation(
            ADR_INDEX,
            line,
            "adr.index",
            "ADR index row must contain one four-digit number and one Markdown link",
            "use `| NNNN | [Decision title](NNNN-short-title.md) |`",
        )

    fun missingEntry(record: AdrRecord) =
        adrViolation(
            ADR_INDEX,
            1,
            "adr.index",
            "ADR ${record.number.padded()} is absent from the index",
            "add exactly one index row linking `${record.filename()}`",
        )

    fun duplicateEntry(
        record: AdrRecord,
        duplicate: AdrIndexEntry,
    ) = adrViolation(
        ADR_INDEX,
        duplicate.line,
        "adr.index",
        "ADR ${record.number.padded()} appears more than once in the index",
        "retain exactly one index row",
    )

    fun wrongDestination(
        record: AdrRecord,
        entry: AdrIndexEntry,
    ) = adrViolation(
        ADR_INDEX,
        entry.line,
        "adr.index",
        "ADR ${record.number.padded()} index link does not match its checked-in filename",
        "link `${record.filename()}`",
    )

    fun staleEntry(entry: AdrIndexEntry) =
        adrViolation(
            ADR_INDEX,
            entry.line,
            "adr.index",
            "ADR index references unpublished record ${entry.number.padded()}",
            "restore the record or remove the stale index row",
        )
}

private data class AdrIndexEntry(
    val number: Int,
    val destination: String,
    val line: Int,
)

private data class IndexedAdrs(
    val entries: List<AdrIndexEntry>,
    val violations: List<Violation>,
)

private fun AdrRecord.filename(): String = document.relativePath.substringAfterLast('/')
