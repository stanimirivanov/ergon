package org.ergon.resolution.domain

import org.ergon.cases.domain.ErgonCase
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import java.time.Instant

/** An immutable case attachment; [attachedAt] is the event's database recording instant, not client time. */
data class DocumentaryBundlePin(
    val reference: DocumentaryBundleRequirement,
    val streamVersion: Long,
    val attachedAt: Instant,
) {
    init {
        require(streamVersion > 0)
    }
}

/** Both absent means unknown; otherwise the selected claim applies on [from, until). */
data class DocumentaryClaimValidity(
    val from: Instant?,
    val until: Instant?,
) {
    init {
        require((from == null) == (until == null))
        require(from == null || from < requireNotNull(until))
    }
}

/** Integrity-checked historical manifest and only its selected claims' immutable validity bounds. */
data class DocumentaryBundleMaterial(
    val reference: DocumentaryBundleRequirement,
    val assessedAt: Instant,
    val claims: List<DocumentaryClaimValidity>,
)

data class PinnedDocumentaryBundle(
    val pin: DocumentaryBundlePin,
    val material: DocumentaryBundleMaterial,
) {
    init {
        require(pin.reference == material.reference)
    }
}

enum class DocumentaryRequirementStatus { MISSING, UNSUITABLE, SATISFIED }

enum class DocumentaryUnsuitability { DIGEST_MISMATCH, MANIFEST_AFTER_ATTACHMENT, UNKNOWN_VALIDITY, OUTSIDE_VALIDITY }

/** Historical attachment qualification only; satisfaction never supplies an operational fact or action grant. */
data class DocumentaryRequirementAssessment(
    val requirement: DocumentaryBundleRequirement,
    val status: DocumentaryRequirementStatus,
    val pin: DocumentaryBundlePin?,
    val reason: DocumentaryUnsuitability? = null,
) {
    init {
        require((status == DocumentaryRequirementStatus.MISSING) == (pin == null))
        require((status == DocumentaryRequirementStatus.UNSUITABLE) == (reason != null))
    }
}

/** Exact identity matching and deterministic validity at attachment; later time/claims never requalify history. */
object DocumentaryEvidenceEvaluator {
    fun assess(
        requirements: List<DocumentaryBundleRequirement>,
        bundles: List<PinnedDocumentaryBundle>,
    ): List<DocumentaryRequirementAssessment> {
        require(bundles.size <= ErgonCase.MAX_DOCUMENTARY_BUNDLES)
        val identities = bundles.map { it.pin.reference.bundleId }
        require(identities.distinct().size == identities.size)
        return requirements.map { requirement ->
            val bundle = bundles.singleOrNull { it.pin.reference.bundleId == requirement.bundleId }
            if (bundle == null) {
                DocumentaryRequirementAssessment(requirement, DocumentaryRequirementStatus.MISSING, null)
            } else {
                val reason = unsuitable(requirement, bundle)
                DocumentaryRequirementAssessment(
                    requirement,
                    if (reason == null) {
                        DocumentaryRequirementStatus.SATISFIED
                    } else {
                        DocumentaryRequirementStatus.UNSUITABLE
                    },
                    bundle.pin,
                    reason,
                )
            }
        }
    }

    private fun unsuitable(
        requirement: DocumentaryBundleRequirement,
        bundle: PinnedDocumentaryBundle,
    ): DocumentaryUnsuitability? {
        val at = bundle.pin.attachedAt
        return when {
            requirement != bundle.pin.reference -> {
                DocumentaryUnsuitability.DIGEST_MISMATCH
            }

            bundle.material.assessedAt > at -> {
                DocumentaryUnsuitability.MANIFEST_AFTER_ATTACHMENT
            }

            bundle.material.claims.any { it.from == null || it.until == null } -> {
                DocumentaryUnsuitability.UNKNOWN_VALIDITY
            }

            bundle.material.claims.any { at < requireNotNull(it.from) || at >= requireNotNull(it.until) } -> {
                DocumentaryUnsuitability.OUTSIDE_VALIDITY
            }

            else -> {
                null
            }
        }
    }
}
