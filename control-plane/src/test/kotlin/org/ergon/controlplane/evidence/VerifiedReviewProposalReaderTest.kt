package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.ClaimProposalService
import org.ergon.controlplane.evidence.application.ProcedureProposalRepository
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.evidence.application.ReviewableEvidenceProposal
import org.ergon.controlplane.evidence.application.StoredClaimProposal
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.controlplane.evidence.application.VerifiedReviewProposalReader
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant

class VerifiedReviewProposalReaderTest {
    private val f = ProcedureFixtures()
    private val claims = Mockito.mock(ClaimProposalRepository::class.java)
    private val procedures = Mockito.mock(ProcedureProposalRepository::class.java)
    private val claimReads = Mockito.mock(ClaimProposalService::class.java)
    private val procedureReads = Mockito.mock(ProcedureProposalService::class.java)
    private val reader = VerifiedReviewProposalReader(claims, procedures, claimReads, procedureReads)

    @Test
    fun `both kinds reverify original source through proposal reads and propagate citation failures`() {
        val procedure = StoredProcedureProposal(f.proposal(), f.actor, Instant.EPOCH)
        val claim =
            StoredClaimProposal(
                ClaimProposal(
                    f.command.id,
                    f.compiled.extraction,
                    0,
                    f.compiled.observations[0].citation,
                    DocumentaryClaim(DocumentaryProperty.SUPPORT, DocumentaryValue.Support(true), "recovery", "staff"),
                ),
                f.actor,
                Instant.EPOCH,
            )
        Mockito.`when`(claims.find(f.tenant, claim.proposal.id)).thenReturn(claim)
        Mockito.`when`(procedures.find(f.tenant, procedure.proposal.id)).thenReturn(procedure)
        Mockito.`when`(claimReads.get(f.markdown.revision, f.actor, f.command.id)).thenReturn(claim)
        Mockito.`when`(procedureReads.get(f.markdown.revision, f.actor, f.command.id)).thenReturn(procedure)
        val claimTarget = EvidenceReviewTarget(EvidenceProposalKind.CLAIM, f.command.id)
        val procedureTarget = claimTarget.copy(kind = EvidenceProposalKind.PROCEDURE)
        assertThat(reader.get(f.tenant, f.actor, claimTarget)).isEqualTo(ReviewableEvidenceProposal.Claim(claim))
        assertThat(reader.get(f.tenant, f.actor, procedureTarget))
            .isEqualTo(ReviewableEvidenceProposal.Procedure(procedure))
        Mockito.doThrow(SourceUnavailable()).`when`(claimReads).get(f.markdown.revision, f.actor, f.command.id)
        Mockito.doThrow(SourceUnavailable()).`when`(procedureReads).get(f.markdown.revision, f.actor, f.command.id)
        for (target in listOf(claimTarget, procedureTarget)) {
            assertThatThrownBy { reader.get(f.tenant, f.actor, target) }.isInstanceOf(SourceUnavailable::class.java)
        }
    }
}
