package org.ergon.evidence.domain

import java.util.UUID

/** Exact labels only; different labels are not proof of disjoint real-world applicability. */
data class DocumentaryClaimKey(
    val property: DocumentaryProperty,
    val subject: String,
    val scope: String,
) {
    companion object {
        fun of(claim: DocumentaryClaim) = DocumentaryClaimKey(claim.property, claim.subject, claim.scope)
    }
}

enum class ClaimComparison {
    NOT_COMPARABLE,
    DISJOINT_VALIDITY,
    COMPATIBLE,
    CONFLICT,
    UNDETERMINED,
}

data class ContradictionFinding(
    val left: UUID,
    val right: UUID,
    val comparison: ClaimComparison,
)

/** Pinned v1 semantics: opposing booleans prove incompatibility; arbitrary text does not. */
object DocumentaryContradictions {
    const val ALGORITHM = "documentary-contradictions-v1"
    const val MAX_CANDIDATES = 20

    fun compare(
        left: DocumentaryClaim,
        right: DocumentaryClaim,
    ): ClaimComparison {
        val leftFrom = left.validFrom
        val rightFrom = right.validFrom
        return when {
            DocumentaryClaimKey.of(left) != DocumentaryClaimKey.of(right) -> {
                ClaimComparison.NOT_COMPARABLE
            }

            leftFrom == null || rightFrom == null -> {
                ClaimComparison.UNDETERMINED
            }

            leftFrom >= checkNotNull(right.validUntil) || rightFrom >= checkNotNull(left.validUntil) -> {
                ClaimComparison.DISJOINT_VALIDITY
            }

            left.value == right.value -> {
                ClaimComparison.COMPATIBLE
            }

            left.value is DocumentaryValue.Support && right.value is DocumentaryValue.Support -> {
                ClaimComparison.CONFLICT
            }

            else -> {
                ClaimComparison.UNDETERMINED
            }
        }
    }

    /** Input is a complete bounded, canonical candidate set, never a truncated page. */
    fun assess(candidates: List<ClaimProposal>): List<ContradictionFinding> {
        require(candidates.size in 1..MAX_CANDIDATES)
        require(candidates.map { it.id }.distinct().size == candidates.size)
        require(candidates.map { it.id.toString() } == candidates.map { it.id.toString() }.sorted())
        require(candidates.map { it.extraction.source.tenantId }.distinct().size == 1)
        return candidates.flatMapIndexed { index, left ->
            candidates.drop(index + 1).map { right ->
                ContradictionFinding(left.id, right.id, compare(left.claim, right.claim))
            }
        }
    }
}

enum class ContradictionChoice { PREFER_CLAIM, DEFER }

/** Immutable exact intent, including all assessed candidates; preference never edits or rejects another claim. */
data class ContradictionCommand(
    val id: UUID,
    val anchorId: UUID,
    val expectedRevision: Long,
    val candidateIds: List<UUID>,
    val choice: ContradictionChoice,
    val selectedClaimId: UUID?,
    val rationale: String,
) {
    init {
        require(expectedRevision >= 1)
        require(candidateIds.size in 2..DocumentaryContradictions.MAX_CANDIDATES)
        require(candidateIds.distinct().size == candidateIds.size && anchorId in candidateIds)
        require(candidateIds.map { it.toString() } == candidateIds.map { it.toString() }.sorted())
        require((choice == ContradictionChoice.PREFER_CLAIM) == (selectedClaimId != null))
        require(selectedClaimId == null || selectedClaimId in candidateIds)
        require(rationale.isNotBlank() && rationale == rationale.trim() && rationale.length <= MAX_RATIONALE_LENGTH)
        require(rationale.none { it.isISOControl() && it !in "\r\n\t" })
        require(Charsets.UTF_8.newEncoder().canEncode(rationale))
    }

    companion object {
        const val MAX_RATIONALE_LENGTH = 2000
    }
}
