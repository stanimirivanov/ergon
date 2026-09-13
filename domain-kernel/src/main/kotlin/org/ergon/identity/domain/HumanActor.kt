package org.ergon.identity.domain

import java.util.UUID

/** Stable tenant-scoped identity of one human who may supply authority evidence. */
@JvmInline
value class HumanActorId(
    val value: UUID,
)

/**
 * Immutable binding between an Ergon actor and an external identity-provider subject.
 *
 * [identityProvider] is a stable machine identifier rather than a display name. [subject]
 * is opaque to Ergon: it must be compared only within that provider and must not contain
 * credentials or bearer tokens. Both values are normalized and bounded by [create].
 */
@ConsistentCopyVisibility
data class HumanActor private constructor(
    val id: HumanActorId,
    val identityProvider: String,
    val subject: String,
) {
    companion object {
        const val MAX_IDENTITY_PROVIDER_LENGTH = 100
        const val MAX_SUBJECT_LENGTH = 255
        const val IDENTITY_PROVIDER_PATTERN = "[a-z0-9][a-z0-9._-]*"

        /**
         * Creates an actor identity after normalizing its external binding.
         *
         * @throws IllegalArgumentException when the provider is not a stable machine
         *   identifier or the opaque subject is blank or exceeds [MAX_SUBJECT_LENGTH].
         */
        fun create(
            id: HumanActorId,
            identityProvider: String,
            subject: String,
        ): HumanActor {
            val normalizedProvider = identityProvider.trim()
            val normalizedSubject = subject.trim()
            require(normalizedProvider.matches(Regex(IDENTITY_PROVIDER_PATTERN))) {
                "identity provider must match $IDENTITY_PROVIDER_PATTERN"
            }
            require(normalizedProvider.length <= MAX_IDENTITY_PROVIDER_LENGTH) {
                "identity provider must not exceed $MAX_IDENTITY_PROVIDER_LENGTH characters"
            }
            require(normalizedSubject.isNotEmpty()) { "identity subject must not be blank" }
            require(normalizedSubject.length <= MAX_SUBJECT_LENGTH) {
                "identity subject must not exceed $MAX_SUBJECT_LENGTH characters"
            }
            return HumanActor(id, normalizedProvider, normalizedSubject)
        }
    }
}
