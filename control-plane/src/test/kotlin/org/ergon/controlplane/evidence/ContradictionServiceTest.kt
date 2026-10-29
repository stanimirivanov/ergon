package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.ContradictionConflict
import org.ergon.controlplane.evidence.application.ContradictionRepository
import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.evidence.application.ContradictionSetTooLarge
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.evidence.application.InvalidContradiction
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class ContradictionServiceTest {
    private val f = ContradictionFixtures()
    private val tenant = f.source.tenant
    private val actor = f.source.actor
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val sources = Mockito.mock(SourceRevisionService::class.java)
    private val repository = Mockito.mock(ContradictionRepository::class.java)
    private val service = ContradictionService(authorities, sources, repository, Clock.fixed(f.now, ZoneOffset.UTC))

    @Test
    fun `authorization precedes private assessment history and adjudication reads`() {
        assertThatThrownBy { service.assess(tenant, actor, f.command.anchorId) }
            .isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { service.adjudicate(tenant, actor, f.command) }.isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { service.get(tenant, actor, f.command.id) }.isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(sources, repository)
    }

    @Test
    fun `accepted exact citations and candidate revisions gate new adjudication`() {
        allow()
        Mockito.`when`(repository.capture(tenant, f.command.anchorId)).thenReturn(f.set)
        assertThat(service.assess(tenant, actor, f.command.anchorId).findings).isEqualTo(f.assessed.findings)
        val admission = EvidenceReviewAdmission(tenant, actor, f.authority)
        Mockito.`when`(repository.register(admission, f.assessed, f.command)).thenReturn(f.stored() to true)
        allowHistory()
        assertThat(service.adjudicate(tenant, actor, f.command).created).isTrue()
        assertThatThrownBy { service.adjudicate(tenant, actor, f.command.copy(expectedRevision = 1)) }
            .isInstanceOf(ContradictionConflict::class.java)
        val changed = f.command.copy(candidateIds = f.command.candidateIds + UUID(0, 12))
        assertThatThrownBy { service.adjudicate(tenant, actor, changed) }
            .isInstanceOf(ContradictionConflict::class.java)
        Mockito.doThrow(SourceUnavailable()).`when`(sources).get(f.source.markdown.revision, actor)
        assertThatThrownBy { service.assess(tenant, actor, f.command.anchorId) }
            .isInstanceOf(SourceUnavailable::class.java)
    }

    @Test
    fun `historical receipt survives later acceptance and requires exact intent and fresh proof`() {
        allow()
        allowHistory()
        Mockito.`when`(repository.find(tenant, f.command.id)).thenReturn(f.stored())
        Mockito.`when`(repository.currentRevision(tenant, f.key)).thenReturn(3)
        val admission = EvidenceReviewAdmission(tenant, actor, f.authority)
        Mockito.`when`(repository.register(admission, f.assessed, f.command)).thenReturn(f.stored() to false)
        val recovered = service.adjudicate(tenant, actor, f.command)
        assertThat(recovered.created).isFalse()
        assertThat(recovered.view.current).isFalse()
        assertThat(recovered.view.stored).isEqualTo(f.stored())
        assertThatThrownBy { service.adjudicate(tenant, actor, f.command.copy(rationale = "changed")) }
            .isInstanceOf(ContradictionConflict::class.java)
        assertThat(service.get(tenant, actor, f.command.id).current).isFalse()
        val renewed = ApprovalAuthorityEvidenceId(UUID(0, 31))
        allow(renewed)
        Mockito
            .`when`(repository.register(EvidenceReviewAdmission(tenant, actor, renewed), f.assessed, f.command))
            .thenReturn(f.stored() to false)
        assertThat(
            service
                .adjudicate(tenant, actor, f.command)
                .view.stored.authority,
        ).isEqualTo(f.authority)
    }

    @Test
    fun `oversized or corrupted stored assessments fail closed`() {
        allow()
        Mockito
            .`when`(repository.capture(tenant, f.command.anchorId))
            .thenReturn(f.set.copy(candidates = (1L..21L).map { f.candidate(it, true) }))
        assertThatThrownBy { service.assess(tenant, actor, f.command.anchorId) }
            .isInstanceOf(ContradictionSetTooLarge::class.java)
        allowHistory()
        Mockito
            .`when`(repository.find(tenant, f.command.id))
            .thenReturn(f.stored().copy(findingCodes = emptyList()))
        assertThatThrownBy { service.get(tenant, actor, f.command.id) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `compatible only sets do not require adjudication and fabricated quotes fail verification`() {
        allow()
        val second = f.candidates.last()
        val proposal = second.stored.proposal
        val compatible =
            second.copy(
                stored =
                    second.stored.copy(
                        proposal =
                            proposal.copy(
                                claim =
                                    f.candidates
                                        .first()
                                        .stored.proposal.claim,
                            ),
                    ),
            )
        Mockito
            .`when`(repository.capture(tenant, f.command.anchorId))
            .thenReturn(f.set.copy(candidates = listOf(f.candidates.first(), compatible)))
        assertThatThrownBy { service.adjudicate(tenant, actor, f.command) }
            .isInstanceOf(InvalidContradiction::class.java)
        val forged = proposal.citation.copy(quote = proposal.citation.quote.replace("Recovery", "Modified"))
        val corrupt = second.copy(stored = second.stored.copy(proposal = proposal.copy(citation = forged)))
        Mockito
            .`when`(repository.capture(tenant, f.command.anchorId))
            .thenReturn(f.set.copy(candidates = listOf(f.candidates.first(), corrupt)))
        assertThatThrownBy { service.assess(tenant, actor, f.command.anchorId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun allowHistory() {
        for (candidate in f.candidates) {
            Mockito.`when`(repository.candidate(tenant, candidate.stored.proposal.id)).thenReturn(candidate)
        }
        Mockito.`when`(repository.currentRevision(tenant, f.key)).thenReturn(2)
    }

    private fun allow(authority: ApprovalAuthorityEvidenceId = f.authority) {
        val proof =
            ApprovalAuthorityEvidence(
                authority,
                actor,
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("test", "resolvers"),
                f.now.minusSeconds(60),
                f.now.plusSeconds(60),
            )
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, f.now))
            .thenReturn(StoredApprovalAuthorityEvidence(proof, f.now))
        Mockito
            .`when`(sources.get(f.source.markdown.revision, actor))
            .thenReturn(StoredSourceRevision(f.source.markdown, actor, f.now))
    }
}
