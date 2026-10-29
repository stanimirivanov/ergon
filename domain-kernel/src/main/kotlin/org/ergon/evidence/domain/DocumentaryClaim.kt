package org.ergon.evidence.domain

import java.time.Instant
import java.util.UUID

private const val MAX_REQUIREMENT_LENGTH = 1000
private const val MAX_LABEL_LENGTH = 256
private const val NANOS_PER_MICROSECOND = 1000
private val MIN_VALIDITY = Instant.parse("0001-01-01T00:00:00Z")
private val MAX_VALIDITY = Instant.parse("9999-12-31T23:59:59.999999Z")

/** Registered documentary meanings, deliberately separate from operational account facts. */
enum class DocumentaryProperty(
    val key: String,
) {
    SUPPORT("documentation.access.restoration.supported"),
    REQUIREMENT("documentation.access.restoration.requirement"),
}

/** Values preserve type: a textual 'true' is not a boolean claim. */
sealed interface DocumentaryValue {
    data class Support(
        val value: Boolean,
    ) : DocumentaryValue

    data class Requirement(
        val value: String,
    ) : DocumentaryValue {
        init {
            require(value.isNotBlank() && value.length <= MAX_REQUIREMENT_LENGTH && value.none { it.isISOControl() })
        }
    }
}

/** Exact subject/scope strings are applicability labels, not inferred assets or authorization scopes. */
data class DocumentaryClaim(
    val property: DocumentaryProperty,
    val value: DocumentaryValue,
    val subject: String,
    val scope: String,
    /** Both absent means unknown validity, never unbounded validity. Otherwise [validFrom, validUntil). */
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
) {
    init {
        require(subject.isNotBlank() && subject == subject.trim() && subject.length <= MAX_LABEL_LENGTH)
        require(scope.isNotBlank() && scope == scope.trim() && scope.length <= MAX_LABEL_LENGTH)
        require((subject + scope).none { it.isISOControl() })
        require((property == DocumentaryProperty.SUPPORT) == (value is DocumentaryValue.Support))
        require((validFrom == null) == (validUntil == null))
        if (validFrom != null && validUntil != null) {
            require(validFrom < validUntil)
            require(validFrom >= MIN_VALIDITY && validUntil <= MAX_VALIDITY)
            val precise = validFrom.nano % NANOS_PER_MICROSECOND == 0 && validUntil.nano % NANOS_PER_MICROSECOND == 0
            require(precise) { "validity requires microsecond precision" }
        }
    }
}

/** Immutable proposal identity/provenance; constructing this grants no authority or review acceptance. */
data class ClaimProposal(
    val id: UUID,
    val extraction: EvidenceExtraction,
    val observationIndex: Int,
    val citation: SourceCitation,
    val claim: DocumentaryClaim,
) {
    init {
        require(observationIndex >= 0 && extraction.source == citation.revision)
    }

    companion object {
        const val BINDING_RULE = "manual-claim-v1"
    }
}
