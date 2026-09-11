package org.ergon.cases.domain

import java.time.Instant
import java.util.UUID

/** Identifies one tenant boundary. */
@JvmInline
value class TenantId(
    val value: UUID,
)

/** Identifies a case independently of any channel or ticket. */
@JvmInline
value class CaseId(
    val value: UUID,
)

/** Identifies an attributable observation in a case history. */
@JvmInline
value class ObservationId(
    val value: UUID,
)

/** The outcome a requester wants Ergon to achieve. */
@ConsistentCopyVisibility
data class CaseGoal private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH = 500

        /** Creates a normalized goal or rejects a blank or oversized value. */
        fun of(value: String): CaseGoal {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "case goal must not be blank" }
            require(normalized.length <= MAX_LENGTH) { "case goal must not exceed $MAX_LENGTH characters" }
            return CaseGoal(normalized)
        }
    }
}

/** Closed set of source roles supported by the first case slice. */
enum class ObservationOriginType {
    REQUESTER,
    CONNECTOR,
}

/** Identifies where an observation came from without assigning semantic meaning to it. */
@ConsistentCopyVisibility
data class ObservationOrigin private constructor(
    val type: ObservationOriginType,
    val provider: String,
    val reference: String?,
) {
    companion object {
        const val MAX_PROVIDER_LENGTH = 100
        const val MAX_REFERENCE_LENGTH = 500
        const val PROVIDER_PATTERN = "[a-z0-9][a-z0-9._-]*"

        /** Represents a request received directly through the API. */
        fun requesterApi(): ObservationOrigin =
            ObservationOrigin(
                type = ObservationOriginType.REQUESTER,
                provider = "api",
                reference = null,
            )

        /** Creates a normalized connector origin with an addressable source reference. */
        fun connector(
            provider: String,
            reference: String,
        ): ObservationOrigin {
            val normalizedProvider = provider.trim()
            val normalizedReference = reference.trim()
            require(normalizedProvider.matches(Regex(PROVIDER_PATTERN))) {
                "connector provider must match $PROVIDER_PATTERN"
            }
            require(normalizedProvider.length <= MAX_PROVIDER_LENGTH) {
                "connector provider must not exceed $MAX_PROVIDER_LENGTH characters"
            }
            require(normalizedReference.isNotEmpty()) { "connector reference must not be blank" }
            require(normalizedReference.length <= MAX_REFERENCE_LENGTH) {
                "connector reference must not exceed $MAX_REFERENCE_LENGTH characters"
            }
            return ObservationOrigin(
                type = ObservationOriginType.CONNECTOR,
                provider = normalizedProvider,
                reference = normalizedReference,
            )
        }
    }
}

/** An attributable source observation that has not yet been semantically bound to a fact. */
@ConsistentCopyVisibility
data class SourceObservation private constructor(
    val id: ObservationId,
    val origin: ObservationOrigin,
    val content: String,
    val observedAt: Instant,
) {
    companion object {
        const val MAX_CONTENT_LENGTH = 8_000

        /** Creates normalized source content while preserving its source and occurrence time. */
        fun create(
            id: ObservationId,
            origin: ObservationOrigin,
            content: String,
            observedAt: Instant,
        ): SourceObservation {
            val normalized = content.trim()
            require(normalized.isNotEmpty()) { "observation content must not be blank" }
            require(normalized.length <= MAX_CONTENT_LENGTH) {
                "observation content must not exceed $MAX_CONTENT_LENGTH characters"
            }
            return SourceObservation(id, origin, normalized, observedAt)
        }
    }
}

/** Lifecycle states supported by the current case kernel. */
enum class CaseStatus {
    OPEN,
}
