package org.ergon.evidence.domain

import java.util.UUID

/** Separate namespaces allow a claim and procedure to have the same opaque proposal UUID. */
enum class EvidenceProposalKind(
    val queueOrder: Int,
) {
    CLAIM(0),
    PROCEDURE(1),
}

data class EvidenceReviewTarget(
    val kind: EvidenceProposalKind,
    val id: UUID,
)

/** Human documentary review only; ACCEPTED is not operational truth, promotion or execution authority. */
enum class EvidenceReviewDecision {
    ACCEPTED,
    REJECTED,
}

/** Exact immutable intent. Version zero is pending; a terminal decision has version one. */
data class EvidenceReviewCommand(
    val id: UUID,
    val extractionId: UUID,
    val expectedVersion: Long,
    val decision: EvidenceReviewDecision,
    val rationale: String,
) {
    init {
        require(expectedVersion >= 0)
        require(rationale.isNotBlank() && rationale == rationale.trim() && rationale.length <= MAX_RATIONALE_LENGTH)
        require(rationale.none { it.isISOControl() && it !in "\r\n\t" })
        require(Charsets.UTF_8.newEncoder().canEncode(rationale))
    }

    companion object {
        const val MAX_RATIONALE_LENGTH = 2000
        const val PENDING_VERSION = 0L
        const val DECIDED_VERSION = 1L
    }
}
