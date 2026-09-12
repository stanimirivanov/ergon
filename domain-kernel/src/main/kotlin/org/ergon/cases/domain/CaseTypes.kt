package org.ergon.cases.domain

import java.time.Instant
import java.util.UUID

/**
 * Identifies one tenant boundary.
 *
 * Every query and write in this bounded context must be scoped by [TenantId]—
 * there is no cross-tenant lookup path by design. Dropping this scope from a
 * query is a data leak, not a convenience.
 */
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

/**
 * The outcome a requester wants Ergon to achieve, as free text.
 *
 * Always trimmed, non-blank, and at most [MAX_LENGTH] characters. These
 * constraints are enforced at construction, so any [CaseGoal] in hand is
 * guaranteed valid. There's no
 * public constructor and, thanks to `@ConsistentCopyVisibility`, no public
 * `copy()` either; both would otherwise let a caller construct an invalid
 * instance. Use [of].
 */
@ConsistentCopyVisibility
data class CaseGoal private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH = 500

        /**
         * Creates a goal after removing leading and trailing whitespace.
         *
         * @return a valid goal containing the trimmed [value].
         * @throws IllegalArgumentException if [value] is blank after trimming,
         *   or exceeds [MAX_LENGTH] characters once trimmed.
         */
        fun of(value: String): CaseGoal {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "case goal must not be blank" }
            require(normalized.length <= MAX_LENGTH) { "case goal must not exceed $MAX_LENGTH characters" }
            return CaseGoal(normalized)
        }
    }
}

/**
 * Closed set of source roles supported by the current case slice.
 *
 * The enum names are persisted and mirror the `observation_origin_type`
 * `CHECK` constraint. Adding a value therefore requires a matching migration,
 * or writes will fail at the database rather than at the type system.
 */
enum class ObservationOriginType {
    REQUESTER,
    CONNECTOR,
}

/**
 * Identifies where an observation came from, without assigning it any
 * semantic meaning—that is a later concern, not this type's job.
 *
 * A [REQUESTER][ObservationOriginType.REQUESTER] origin always has provider
 * `"api"` and no reference. A [CONNECTOR][ObservationOriginType.CONNECTOR]
 * origin always has both. Build one via [requesterApi] or [connector];
 * there's no other way to end up with a value of this type, which is what
 * keeps those two shapes from drifting apart.
 */
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

        /** @return the canonical requester origin: provider `"api"` with no external reference. */
        fun requesterApi(): ObservationOrigin =
            ObservationOrigin(
                type = ObservationOriginType.REQUESTER,
                provider = "api",
                reference = null,
            )

        /**
         * Creates a connector origin whose provider and reference can be
         * stored as stable source attribution.
         *
         * @param provider trimmed, limited to [MAX_PROVIDER_LENGTH] characters,
         *   and matched against [PROVIDER_PATTERN] so it remains a stable,
         *   machine-readable identifier rather than free text.
         * @param reference an opaque pointer back to the connector's own
         *   record; trimmed, non-blank, and at most [MAX_REFERENCE_LENGTH]
         *   characters.
         * @throws IllegalArgumentException if [provider] doesn't match
         *   [PROVIDER_PATTERN], [reference] is blank, or either argument
         *   exceeds its length limit.
         */
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

/**
 * Attributable source material captured before semantic binding turns it into
 * domain facts.
 *
 * [content] is trimmed, non-blank, and at most [MAX_CONTENT_LENGTH]
 * characters. [id], [origin], and [observedAt] are retained unchanged so
 * downstream processing can preserve source attribution and occurrence time.
 * The private constructor and copy function keep those invariants centralized
 * in [create].
 */
@ConsistentCopyVisibility
data class SourceObservation private constructor(
    val id: ObservationId,
    val origin: ObservationOrigin,
    val content: String,
    val observedAt: Instant,
) {
    companion object {
        const val MAX_CONTENT_LENGTH = 8_000

        /**
         * Creates an observation after removing leading and trailing
         * whitespace from [content].
         *
         * @return an observation with normalized content and unchanged source
         *   attribution and occurrence time.
         * @throws IllegalArgumentException if [content] is blank after
         *   trimming or exceeds [MAX_CONTENT_LENGTH] characters once trimmed.
         */
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

/**
 * Closed set of lifecycle states supported by the current case kernel.
 *
 * The enum names are persisted and mirror the `cases.status` `CHECK`
 * constraint. Adding a state requires a matching migration and persistence
 * handling in the same change.
 */
enum class CaseStatus {
    OPEN,
}
