package org.ergon.evidence.domain

import java.time.Instant
import java.util.UUID

/** Exact input from an existing assessment; a singleton still requires known validity. */
data class BundleClaimSetSelection(
    val anchorId: UUID,
    val revision: Long,
    val candidateIds: List<UUID>,
    val adjudicationId: UUID?,
) {
    init {
        require(revision > 0 && candidateIds.size in 1..DocumentaryContradictions.MAX_CANDIDATES)
        require(candidateIds.isCanonicalIds() && anchorId in candidateIds)
    }
}

/** Identity is idempotent within a tenant. Lists are canonical, bounded, exact intent, never implicit latest. */
data class EvidenceBundleCommand(
    val id: UUID,
    val claims: List<UUID>,
    val procedures: List<UUID>,
    val claimSets: List<BundleClaimSetSelection>,
) {
    init {
        require(claims.size + procedures.size in 1..MAX_SELECTED)
        require(claims.isCanonicalIds() && procedures.isCanonicalIds())
        require(claimSets.size <= claims.size && claimSets.map { it.anchorId }.isCanonicalIds())
    }

    companion object {
        const val MAX_SELECTED = 20
    }
}

data class BundleProposalReference(
    val target: EvidenceReviewTarget,
    val extraction: EvidenceExtraction,
    val reviewCommandId: UUID,
    val selected: Boolean,
)

data class BundleClaimSet(
    val key: DocumentaryClaimKey,
    val revision: Long,
    val candidateIds: List<UUID>,
    val reviewCommandIds: List<UUID>,
    val findingCodes: List<ClaimComparison>,
    val adjudicationId: UUID?,
) {
    init {
        require(revision > 0 && candidateIds.size in 1..DocumentaryContradictions.MAX_CANDIDATES)
        require(candidateIds.isCanonicalIds() && reviewCommandIds.size == candidateIds.size)
        require(reviewCommandIds.distinct().size == reviewCommandIds.size)
        require(findingCodes.size == candidateIds.size * (candidateIds.size - 1) / 2)
        require(findingCodes.none { it == ClaimComparison.UNDETERMINED || it == ClaimComparison.NOT_COMPARABLE })
        require((ClaimComparison.CONFLICT in findingCodes) == (adjudicationId != null))
    }
}

/** Complete assessed alternatives are retained, with selected entries distinguished from audit context. */
data class EvidenceBundleContent(
    val proposals: List<BundleProposalReference>,
    val sources: List<SourceRevision>,
    val claimSets: List<BundleClaimSet>,
) {
    init {
        require(
            proposals.size in 1..MAX_REFERENCES &&
                proposals.count { it.selected } in 1..EvidenceBundleCommand.MAX_SELECTED,
        )
        require(proposals.map { it.target }.distinct().size == proposals.size)
        require(proposals == proposals.sortedWith(compareBy({ it.target.kind.name }, { it.target.id.toString() })))
        require(sources.size in 1..MAX_REFERENCES && sources.distinct().size == sources.size)
        require(sources.map { it.tenantId }.distinct().size == 1)
        require(sources == sources.sortedWith(compareBy({ it.sourceId.value.toString() }, { it.digest.value })))
        require(proposals.all { it.extraction.source in sources })
        require(
            claimSets.size <= EvidenceBundleCommand.MAX_SELECTED &&
                claimSets.map { it.key }.distinct().size == claimSets.size,
        )
        val canonicalSets =
            claimSets.sortedWith(
                compareBy({ it.key.property.name }, { it.key.subject }, { it.key.scope }),
            )
        require(claimSets == canonicalSets)
        val claims = proposals.filter { it.target.kind == EvidenceProposalKind.CLAIM }
        require(claims.map { it.target.id }.toSet() == claimSets.flatMap { it.candidateIds }.toSet())
        require(claimSets.flatMap { it.candidateIds }.distinct().size == claims.size)
        require(proposals.filter { it.target.kind == EvidenceProposalKind.PROCEDURE }.all { it.selected })
    }

    companion object {
        const val MAX_REFERENCES = 420
    }
}

/** Immutable documentary cutoff, never continuing consistency or execution authority. */
data class EvidenceBundleManifest(
    val assessedAt: Instant,
    val content: EvidenceBundleContent,
) {
    init {
        require(assessedAt.nano % NANOS_PER_MICROSECOND == 0)
    }

    companion object {
        const val SCHEMA = "ergon.dev/evidence-bundle/v1"
        private const val NANOS_PER_MICROSECOND = 1000
    }
}

/** The caller validates preferred identity against an exact current immutable adjudication first. */
object EvidenceBundleQualification {
    fun qualify(
        claims: List<ClaimProposal>,
        selected: Set<UUID>,
        preferred: UUID?,
    ): List<ContradictionFinding> {
        require(selected.isNotEmpty() && claims.map { it.id }.containsAll(selected))
        require(claims.map { DocumentaryClaimKey.of(it.claim) }.distinct().size == 1)
        require(claims.all { it.claim.validFrom != null && it.claim.validUntil != null })
        val findings = DocumentaryContradictions.assess(claims)
        require(findings.none { it.comparison in setOf(ClaimComparison.UNDETERMINED, ClaimComparison.NOT_COMPARABLE) })
        if (findings.any { it.comparison == ClaimComparison.CONFLICT }) {
            require(selected.size == 1 && preferred == selected.single())
        } else {
            require(preferred == null)
        }
        return findings
    }
}

private fun List<UUID>.isCanonicalIds(): Boolean {
    val text = map { it.toString() }
    return distinct().size == size && text == text.sorted()
}
