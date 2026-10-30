package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.ContradictionAdjudicationView
import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.evidence.application.EvidenceBundleConflict
import org.ergon.controlplane.evidence.application.EvidenceBundlePreparer
import org.ergon.controlplane.evidence.application.EvidenceReviewProposalReader
import org.ergon.controlplane.evidence.application.EvidenceReviewRepository
import org.ergon.controlplane.evidence.application.InvalidEvidenceBundle
import org.ergon.controlplane.evidence.application.ReviewableEvidenceProposal
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class EvidenceBundlePreparerTest {
    private val b = EvidenceBundleFixtures()
    private val f = b.f
    private val tenant = f.source.tenant
    private val actor = f.source.actor
    private val contradictions = Mockito.mock(ContradictionService::class.java)
    private val proposals = Mockito.mock(EvidenceReviewProposalReader::class.java)
    private val reviews = Mockito.mock(EvidenceReviewRepository::class.java)
    private val preparer = EvidenceBundlePreparer(contradictions, proposals, reviews)

    @Test
    fun `verified complete alternatives primary and API sources enter a canonical manifest`() {
        allow()
        assertThat(preparer.prepare(tenant, actor, b.command)).isEqualTo(b.content)
        assertThat(b.content.sources).containsExactly(f.source.markdown.revision, f.source.api.revision)
        assertThat(b.content.proposals.filter { !it.selected }).hasSize(1)
    }

    @Test
    fun `stale deferred unresolved and undetermined assessments cannot qualify`() {
        allow()
        val selection = b.command.claimSets.single()
        assertThatThrownBy {
            preparer.prepare(
                tenant,
                actor,
                b.command.copy(claimSets = listOf(selection.copy(revision = 1))),
            )
        }.isInstanceOf(EvidenceBundleConflict::class.java)
        assertThatThrownBy {
            preparer.prepare(
                tenant,
                actor,
                b.command.copy(claimSets = listOf(selection.copy(adjudicationId = null))),
            )
        }.isInstanceOf(InvalidEvidenceBundle::class.java)
        val deferCommand = f.command.copy(choice = ContradictionChoice.DEFER, selectedClaimId = null)
        val deferred = f.stored().copy(command = deferCommand)
        Mockito
            .`when`(contradictions.get(tenant, actor, f.command.id))
            .thenReturn(ContradictionAdjudicationView(deferred, f.assessed, true))
        assertThatThrownBy { preparer.prepare(tenant, actor, b.command) }
            .isInstanceOf(InvalidEvidenceBundle::class.java)
        Mockito
            .`when`(contradictions.get(tenant, actor, f.command.id))
            .thenReturn(ContradictionAdjudicationView(f.stored(), f.assessed, false))
        assertThatThrownBy { preparer.prepare(tenant, actor, b.command) }
            .isInstanceOf(EvidenceBundleConflict::class.java)
    }

    @Test
    fun `missing rejected or source-denied procedures never become selected accepted references`() {
        allow()
        Mockito
            .`when`(reviews.find(tenant, b.procedureReview.target))
            .thenReturn(
                b.procedureReview.copy(
                    command = b.procedureReview.command.copy(decision = EvidenceReviewDecision.REJECTED),
                ),
            )
        assertThatThrownBy { preparer.prepare(tenant, actor, b.command) }.isInstanceOf(SourceUnavailable::class.java)
        allow()
        Mockito.doThrow(SourceUnavailable()).`when`(proposals).get(tenant, actor, b.procedureReview.target)
        assertThatThrownBy { preparer.prepare(tenant, actor, b.command) }.isInstanceOf(SourceUnavailable::class.java)
    }

    private fun allow() {
        Mockito.`when`(contradictions.assess(tenant, actor, f.command.anchorId)).thenReturn(f.assessed)
        Mockito
            .`when`(contradictions.get(tenant, actor, f.command.id))
            .thenReturn(ContradictionAdjudicationView(f.stored(), f.assessed, true))
        Mockito
            .`when`(proposals.get(tenant, actor, b.procedureReview.target))
            .thenReturn(ReviewableEvidenceProposal.Procedure(b.procedure))
        Mockito.`when`(reviews.find(tenant, b.procedureReview.target)).thenReturn(b.procedureReview)
    }
}
