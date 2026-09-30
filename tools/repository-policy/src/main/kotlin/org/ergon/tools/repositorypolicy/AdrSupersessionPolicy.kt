package org.ergon.tools.repositorypolicy

internal object AdrSupersessionPolicy {
    fun check(records: List<AdrRecord>): List<Violation> {
        val byNumber = records.associateBy { it.number }
        return records.flatMap { record ->
            validateDirection(record, "Supersedes", "Superseded by", byNumber) +
                validateDirection(record, "Superseded by", "Supersedes", byNumber)
        }
    }

    private fun validateDirection(
        record: AdrRecord,
        key: String,
        reciprocalKey: String,
        records: Map<Int, AdrRecord>,
    ): List<Violation> =
        record.references(key).mapNotNull { targetNumber ->
            val target = records[targetNumber]
            when {
                target == null -> {
                    missingTarget(record, key, targetNumber)
                }

                record.number !in target.references(reciprocalKey) -> {
                    missingReciprocal(record, target, reciprocalKey)
                }

                else -> {
                    null
                }
            }
        }

    private fun missingTarget(
        record: AdrRecord,
        key: String,
        target: Int,
    ) = adrViolation(
        record.document.relativePath,
        record.metadata[key]?.line ?: 1,
        "adr.supersession",
        "ADR ${record.number.padded()} references missing ADR ${target.padded()} in `$key`",
        "restore the referenced record or correct the supersession metadata",
    )

    private fun missingReciprocal(
        record: AdrRecord,
        target: AdrRecord,
        reciprocalKey: String,
    ) = adrViolation(
        record.document.relativePath,
        record.title?.line ?: 1,
        "adr.supersession",
        "ADR ${record.number.padded()} and ADR ${target.number.padded()} do not declare reciprocal supersession",
        "add ADR ${record.number.padded()} to `${target.document.relativePath}` metadata `$reciprocalKey`",
    )
}
