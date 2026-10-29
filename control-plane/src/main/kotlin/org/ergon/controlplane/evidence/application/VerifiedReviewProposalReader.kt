package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId

/** Reuses proposal-owned exact-source verification without reparsing historical content under newer compilers. */
class VerifiedReviewProposalReader(
    private val claims: ClaimProposalRepository,
    private val procedures: ProcedureProposalRepository,
    private val claimReads: ClaimProposalService,
    private val procedureReads: ProcedureProposalService,
) : EvidenceReviewProposalReader {
    override fun get(
        tenant: TenantId,
        actor: HumanActorId,
        target: EvidenceReviewTarget,
    ): ReviewableEvidenceProposal =
        when (target.kind) {
            EvidenceProposalKind.CLAIM -> {
                val stored = claims.find(tenant, target.id) ?: throw SourceUnavailable()
                ReviewableEvidenceProposal.Claim(claimReads.get(stored.proposal.citation.revision, actor, target.id))
            }

            EvidenceProposalKind.PROCEDURE -> {
                val stored = procedures.find(tenant, target.id) ?: throw SourceUnavailable()
                ReviewableEvidenceProposal.Procedure(
                    procedureReads.get(stored.proposal.content.citation.revision, actor, target.id),
                )
            }
        }
}
