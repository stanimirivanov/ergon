package org.ergon.resolution.domain

import org.ergon.cases.domain.CaseId
import org.ergon.identity.domain.HumanActorId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable attestation that a human holds approval authority. */
@JvmInline
value class ApprovalAuthorityEvidenceId(
    val value: UUID,
)

/** Clock-derived usability of authority evidence; expiry never rewrites the audit record. */
enum class ApprovalAuthorityEvidenceStatus {
    CURRENT,
    EXPIRED,
}

/**
 * Attributable source of an authority attestation.
 *
 * The provider is a stable machine identifier and the reference is an opaque pointer to
 * the provider's record. Neither value is authentication material.
 */
@ConsistentCopyVisibility
data class ApprovalAuthorityEvidenceSource private constructor(
    val provider: String,
    val reference: String,
) {
    companion object {
        const val MAX_PROVIDER_LENGTH = 100
        const val MAX_REFERENCE_LENGTH = 500
        const val PROVIDER_PATTERN = "[a-z0-9][a-z0-9._-]*"

        /**
         * Creates a source that can be traced back to the attesting system.
         *
         * @throws IllegalArgumentException when either value is invalid or cannot be
         *   retained as bounded, stable attribution.
         */
        fun create(
            provider: String,
            reference: String,
        ): ApprovalAuthorityEvidenceSource {
            val normalizedProvider = provider.trim()
            val normalizedReference = reference.trim()
            require(normalizedProvider.matches(Regex(PROVIDER_PATTERN))) {
                "authority evidence provider must match $PROVIDER_PATTERN"
            }
            require(normalizedProvider.length <= MAX_PROVIDER_LENGTH) {
                "authority evidence provider must not exceed $MAX_PROVIDER_LENGTH characters"
            }
            require(normalizedReference.isNotEmpty()) { "authority evidence reference must not be blank" }
            require(normalizedReference.length <= MAX_REFERENCE_LENGTH) {
                "authority evidence reference must not exceed $MAX_REFERENCE_LENGTH characters"
            }
            return ApprovalAuthorityEvidenceSource(normalizedProvider, normalizedReference)
        }
    }
}

/**
 * Immutable, time-bounded evidence that [actorId] holds [authority] in the required scope.
 *
 * Requester authority is meaningful only for one [caseId], while resolver authority is
 * tenant-wide and therefore has no case. The half-open interval ends at [expiresAt], and
 * its maximum duration is [MAX_LIFETIME]. Evidence identifies an attestation; it neither
 * authenticates the actor nor grants approval for a request.
 *
 * @throws IllegalArgumentException when authority and case scope disagree or the validity
 *   interval is empty, negative, or longer than [MAX_LIFETIME].
 */
data class ApprovalAuthorityEvidence(
    val id: ApprovalAuthorityEvidenceId,
    val actorId: HumanActorId,
    val authority: ApprovalAuthority,
    val caseId: CaseId?,
    val source: ApprovalAuthorityEvidenceSource,
    val attestedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require((authority == ApprovalAuthority.REQUESTER) == (caseId != null)) {
            "requester authority must identify one case and resolver authority must be tenant-wide"
        }
        require(expiresAt.isAfter(attestedAt)) { "authority evidence expiry must be after attestation time" }
        require(Duration.between(attestedAt, expiresAt) <= MAX_LIFETIME) {
            "authority evidence lifetime must not exceed $MAX_LIFETIME"
        }
    }

    /** Returns [EXPIRED][ApprovalAuthorityEvidenceStatus.EXPIRED] at and after [expiresAt]. */
    fun statusAt(now: Instant): ApprovalAuthorityEvidenceStatus =
        if (now.isBefore(expiresAt)) {
            ApprovalAuthorityEvidenceStatus.CURRENT
        } else {
            ApprovalAuthorityEvidenceStatus.EXPIRED
        }

    companion object {
        val MAX_LIFETIME: Duration = Duration.ofHours(24)
    }
}
