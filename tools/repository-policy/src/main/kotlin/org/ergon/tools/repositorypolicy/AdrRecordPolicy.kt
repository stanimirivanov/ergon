package org.ergon.tools.repositorypolicy

import java.time.LocalDate
import java.time.format.DateTimeParseException

internal object AdrRecordPolicy {
    private val supportedStatuses = setOf("Proposed", "Accepted", "Rejected", "Deprecated", "Superseded")
    private val milestoneTitle = Regex("M\\d{2} - .+")

    fun check(records: List<AdrRecord>): List<Violation> = records.flatMap(::validate)

    private fun validate(record: AdrRecord): List<Violation> {
        val violations = validateTitle(record) + validateSections(record) + validateMetadata(record)
        return violations
    }

    private fun validateTitle(record: AdrRecord): List<Violation> {
        val currentPrefix = "ADR ${record.number.padded()}:"
        val historicalPrefix = "${record.number.padded()}."
        val text = record.title?.text.orEmpty()
        val allowedPrefix =
            if (record.number >= CURRENT_FORMAT_FIRST_ADR) {
                text.startsWith(currentPrefix)
            } else {
                text.startsWith(currentPrefix) || text.startsWith(historicalPrefix)
            }
        val valid = record.title != null && !record.title.containsRawHtml && allowedPrefix
        return if (valid) {
            emptyList()
        } else {
            listOf(
                adrViolation(
                    record.document.relativePath,
                    record.title?.line ?: 1,
                    "adr.heading",
                    "ADR heading does not identify ${record.number.padded()} in the permitted visible format",
                    titleCorrection(record),
                ),
            )
        }
    }

    private fun titleCorrection(record: AdrRecord): String =
        if (record.number >= CURRENT_FORMAT_FIRST_ADR) {
            "use `# ADR ${record.number.padded()}: Short decision title`"
        } else {
            "restore the historical numbered heading"
        }

    private fun validateSections(record: AdrRecord): List<Violation> {
        val required = requiredSections(record.number)
        val actual =
            record.document.headings
                .filter { it.level == 2 }
                .associateBy { it.text }
        val missing =
            required
                .filter { section -> actual[section] == null }
                .map { section -> AdrRecordViolations.missingSection(record, section) }
        val positions = required.mapNotNull { section -> actual[section]?.line }
        val ordering =
            if (positions == positions.sorted()) {
                emptyList()
            } else {
                listOf(AdrRecordViolations.sectionOrder(record, required))
            }
        return missing + ordering
    }

    private fun validateMetadata(record: AdrRecord): List<Violation> {
        val violations = mutableListOf<Violation>()
        val status = record.metadata["Status"]
        if (!validStatus(record.number, status?.value)) {
            violations += AdrRecordViolations.invalidStatus(record, status)
        }
        val date = record.metadata["Date"]
        if (date == null || !validDate(date.value)) {
            violations += AdrRecordViolations.invalidDate(record, date)
        }
        if (record.number >= CURRENT_FORMAT_FIRST_ADR) {
            violations += validateCurrentMetadata(record)
        }
        return violations
    }

    private fun validStatus(
        number: Int,
        value: String?,
    ): Boolean =
        value != null &&
            (
                supportedStatuses.any { it.equals(value, ignoreCase = true) } ||
                    (number <= PREDECESSOR_LAST_ADR && value == "Superseded by the Ergon rewrite")
            )

    private fun validateCurrentMetadata(record: AdrRecord): List<Violation> {
        val missing =
            REQUIRED_CURRENT_METADATA.mapNotNull { key ->
                val value = record.metadata[key]
                val missingValue = value == null || (key !in EMPTY_ALLOWED_METADATA && value.value.isBlank())
                if (missingValue) AdrRecordViolations.missingMetadata(record, key, value) else null
            }
        val milestone = record.metadata["Milestone"]
        val milestoneViolation =
            if (milestone == null || !milestoneTitle.matches(milestone.value)) {
                listOf(AdrRecordViolations.invalidMilestone(record, milestone))
            } else {
                emptyList()
            }
        return missing + milestoneViolation
    }

    private fun validDate(value: String): Boolean =
        try {
            LocalDate.parse(value)
            true
        } catch (_: DateTimeParseException) {
            false
        }

    private fun requiredSections(number: Int): List<String> =
        when (number) {
            in 1..PREDECESSOR_LAST_ADR -> listOf("Context", "Decision", "Consequences")
            HISTORICAL_MINIMAL_ADR -> listOf("Context", "Decision", "Consequences")
            in (PREDECESSOR_LAST_ADR + 1)..EARLY_TEMPLATE_LAST_ADR -> EARLY_SECTIONS
            in (EARLY_TEMPLATE_LAST_ADR + 1)..TRANSITIONAL_TEMPLATE_LAST_ADR -> TRANSITIONAL_SECTIONS
            in (TRANSITIONAL_TEMPLATE_LAST_ADR + 1)..RECENT_LEGACY_LAST_ADR -> listOf("TL;DR") + CURRENT_SECTIONS
            else -> CURRENT_SECTIONS
        }

    internal val CURRENT_SECTIONS =
        listOf(
            "Context",
            "Decision",
            "Alternatives considered",
            "Consequences",
            "Compatibility and migration",
            "Security and operations",
            "Validation",
        )
    internal val REQUIRED_CURRENT_METADATA =
        listOf("Status", "Date", "Milestone", "Deciders", "Supersedes", "Superseded by")
    private val EMPTY_ALLOWED_METADATA = setOf("Supersedes", "Superseded by")
    private const val HISTORICAL_MINIMAL_ADR = 7
    private val EARLY_SECTIONS = listOf("Context", "Decision", "Alternatives", "Consequences", "Verification")
    private val TRANSITIONAL_SECTIONS =
        listOf(
            "Context",
            "Decision",
            "Alternatives",
            "Consequences",
            "Compatibility and migration",
            "Validation",
        )
}

private object AdrRecordViolations {
    fun missingSection(
        record: AdrRecord,
        section: String,
    ) = adrViolation(
        record.document.relativePath,
        record.title?.line ?: 1,
        "adr.sections",
        "ADR ${record.number.padded()} is missing required section `$section`",
        "restore the historical section or use the current ADR template for new records",
    )

    fun sectionOrder(
        record: AdrRecord,
        required: List<String>,
    ) = adrViolation(
        record.document.relativePath,
        record.title?.line ?: 1,
        "adr.sections",
        "required ADR sections are not in their documented order",
        "order the required sections as `${required.joinToString("`, `")}`",
    )

    fun invalidStatus(
        record: AdrRecord,
        status: MetadataValue?,
    ) = adrViolation(
        record.document.relativePath,
        status?.line ?: record.title?.line ?: 1,
        "adr.lifecycle",
        "ADR ${record.number.padded()} has no supported visible lifecycle status",
        "use Proposed, Accepted, Rejected, Deprecated, or Superseded; " +
            "retain the predecessor status only on ADRs 0001-0005",
    )

    fun invalidDate(
        record: AdrRecord,
        date: MetadataValue?,
    ) = adrViolation(
        record.document.relativePath,
        date?.line ?: record.title?.line ?: 1,
        "adr.lifecycle",
        "ADR ${record.number.padded()} has no valid ISO decision date",
        "add `- Date: YYYY-MM-DD` as visible metadata",
    )

    fun missingMetadata(
        record: AdrRecord,
        key: String,
        value: MetadataValue?,
    ) = adrViolation(
        record.document.relativePath,
        value?.line ?: record.title?.line ?: 1,
        "adr.metadata",
        "current-format ADR is missing visible `$key` metadata",
        "copy the complete metadata block from `$ADR_TEMPLATE`",
    )

    fun invalidMilestone(
        record: AdrRecord,
        milestone: MetadataValue?,
    ) = adrViolation(
        record.document.relativePath,
        milestone?.line ?: 1,
        "adr.metadata",
        "current-format ADR milestone `${milestone?.value.orEmpty()}` does not use `MNN - Outcome`",
        "name one exact published milestone from the roadmap",
    )
}
