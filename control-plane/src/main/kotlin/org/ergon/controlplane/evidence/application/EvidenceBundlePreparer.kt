package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.BundleClaimSet
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.BundleProposalReference
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceBundleQualification
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.util.UUID

/** Citation verification stays outside the final short transaction; admission rechecks exact candidate epochs. */
class EvidenceBundlePreparer(
    private val contradictions: ContradictionService,
    private val proposals: EvidenceReviewProposalReader,
    private val reviews: EvidenceReviewRepository,
) {
    fun prepare(
        tenant: TenantId,
        actor: HumanActorId,
        command: EvidenceBundleCommand,
    ): EvidenceBundleContent {
        val prepared = command.claimSets.map { prepareSet(tenant, actor, command, it) }
        val references = prepared.flatMap { it.references }.toMutableList()
        val sources = references.map { it.extraction.source }.toMutableSet()
        val selectedClaims = references.filter { it.selected }.map { it.target.id }
        if (!selectedClaims.containsAll(command.claims)) throw SourceUnavailable()
        for (id in command.procedures) appendProcedure(tenant, actor, id, references, sources)
        val sets = prepared.map { it.set }
        return try {
            EvidenceBundleContent(
                references.sortedWith(compareBy({ it.target.kind.name }, { it.target.id.toString() })),
                sources.sortedWith(compareBy({ it.sourceId.value.toString() }, { it.digest.value })),
                sets.sortedWith(compareBy({ it.key.property.name }, { it.key.subject }, { it.key.scope })),
            )
        } catch (_: IllegalArgumentException) {
            throw InvalidEvidenceBundle()
        }
    }

    private fun prepareSet(
        tenant: TenantId,
        actor: HumanActorId,
        command: EvidenceBundleCommand,
        selection: BundleClaimSetSelection,
    ): PreparedBundleSet {
        val assessed = contradictions.assess(tenant, actor, selection.anchorId)
        val candidates = assessed.set.candidates
        val ids = candidates.map { it.stored.proposal.id }
        if (assessed.set.revision != selection.revision || ids != selection.candidateIds) {
            throw EvidenceBundleConflict()
        }
        val preferred = preferred(tenant, actor, selection, assessed)
        val selected = command.claims.filter { it in ids }.toSet()
        val findings =
            try {
                EvidenceBundleQualification.qualify(candidates.map { it.stored.proposal }, selected, preferred)
            } catch (_: IllegalArgumentException) {
                throw InvalidEvidenceBundle()
            }
        val references =
            candidates.map {
                BundleProposalReference(
                    EvidenceReviewTarget(EvidenceProposalKind.CLAIM, it.stored.proposal.id),
                    it.stored.proposal.extraction,
                    it.reviewCommandId,
                    it.stored.proposal.id in selected,
                )
            }
        val set =
            BundleClaimSet(
                assessed.set.key,
                selection.revision,
                ids,
                candidates.map { it.reviewCommandId },
                findings.map { it.comparison },
                selection.adjudicationId,
            )
        return PreparedBundleSet(set, references)
    }

    private fun appendProcedure(
        tenant: TenantId,
        actor: HumanActorId,
        id: UUID,
        references: MutableList<BundleProposalReference>,
        sources: MutableSet<SourceRevision>,
    ) {
        val target = EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, id)
        val stored = (proposals.get(tenant, actor, target) as ReviewableEvidenceProposal.Procedure).stored
        val review = reviews.find(tenant, target) ?: throw SourceUnavailable()
        if (review.command.decision != EvidenceReviewDecision.ACCEPTED) throw SourceUnavailable()
        check(review.command.extractionId == stored.proposal.extraction.id)
        references += BundleProposalReference(target, stored.proposal.extraction, review.command.id, true)
        sources += stored.proposal.extraction.source
        stored.proposal.content.steps
            .mapNotNull { it.operation }
            .forEach { sources += it.citation.revision }
    }

    private fun preferred(
        tenant: TenantId,
        actor: HumanActorId,
        selection: BundleClaimSetSelection,
        assessed: AssessedClaimSet,
    ): UUID? {
        val id = selection.adjudicationId ?: return null
        val view = contradictions.get(tenant, actor, id)
        if (!view.current || view.assessment != assessed) throw EvidenceBundleConflict()
        if (view.stored.command.choice != ContradictionChoice.PREFER_CLAIM) throw InvalidEvidenceBundle()
        return view.stored.command.selectedClaimId
    }
}

private data class PreparedBundleSet(
    val set: BundleClaimSet,
    val references: List<BundleProposalReference>,
)
