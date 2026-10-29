package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.evidence.application.EvidenceReviewConflict
import org.ergon.controlplane.evidence.application.EvidenceReviewCursor
import org.ergon.controlplane.evidence.application.EvidenceReviewProposalReader
import org.ergon.controlplane.evidence.application.EvidenceReviewReceipt
import org.ergon.controlplane.evidence.application.EvidenceReviewRegistration
import org.ergon.controlplane.evidence.application.EvidenceReviewRepository
import org.ergon.controlplane.evidence.application.EvidenceReviewService
import org.ergon.controlplane.evidence.application.InvalidEvidenceReview
import org.ergon.controlplane.evidence.application.PendingEvidenceProposal
import org.ergon.controlplane.evidence.application.ReviewableEvidenceProposal
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EvidenceReviewServiceTest {
    private val f = ProcedureFixtures()
    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val proposals = Mockito.mock(EvidenceReviewProposalReader::class.java)
    private val repository = Mockito.mock(EvidenceReviewRepository::class.java)
    private val service = EvidenceReviewService(authorities, proposals, repository, Clock.fixed(now, ZoneOffset.UTC))
    private val target = EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, f.command.id)
    private val command =
        EvidenceReviewCommand(
            UUID(0, 30),
            f.compiled.extraction.id,
            0,
            EvidenceReviewDecision.ACCEPTED,
            "Checked citations",
        )

    @Test
    fun `all reads writes and recovery require fresh tenant resolver before private data access`() {
        assertThatThrownBy { service.decide(f.tenant, f.actor, target, command) }
            .isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { service.inspect(f.tenant, f.actor, target) }.isInstanceOf(SourceUnavailable::class.java)
        assertThatThrownBy { service.pending(f.tenant, f.actor, null, 20) }.isInstanceOf(SourceUnavailable::class.java)
        Mockito.verifyNoInteractions(proposals, repository)
    }

    @Test
    fun `exact review uses verified extraction and current authority while preserving original receipt`() {
        val proof = allow()
        val proposal = ReviewableEvidenceProposal.Procedure(StoredProcedureProposal(f.proposal(), f.actor, now))
        Mockito.`when`(proposals.get(f.tenant, f.actor, target)).thenReturn(proposal)
        val receipt = EvidenceReviewReceipt(target, command, f.actor, proof.evidence.id, now)
        val admission = EvidenceReviewAdmission(f.tenant, f.actor, proof.evidence.id)
        Mockito
            .`when`(repository.decide(admission, target, command))
            .thenReturn(EvidenceReviewRegistration(receipt, true))
        assertThat(service.decide(f.tenant, f.actor, target, command).receipt).isEqualTo(receipt)
        Mockito.`when`(repository.find(f.tenant, target)).thenReturn(receipt)
        assertThat(service.inspect(f.tenant, f.actor, target).receipt).isEqualTo(receipt)
        assertThatThrownBy { service.decide(f.tenant, f.actor, target, command.copy(extractionId = UUID(0, 90))) }
            .isInstanceOf(EvidenceReviewConflict::class.java)
        val renewed = allow(UUID(0, 41))
        Mockito
            .`when`(repository.decide(EvidenceReviewAdmission(f.tenant, f.actor, renewed.evidence.id), target, command))
            .thenReturn(EvidenceReviewRegistration(receipt, false))
        assertThat(service.decide(f.tenant, f.actor, target, command).receipt?.authority).isEqualTo(proof.evidence.id)
        Mockito.doThrow(SourceUnavailable()).`when`(proposals).get(f.tenant, f.actor, target)
        assertThatThrownBy { service.inspect(f.tenant, f.actor, target) }.isInstanceOf(SourceUnavailable::class.java)
    }

    @Test
    fun `queue is bounded and continuation uses last returned exact coordinates`() {
        allow()
        val entries =
            (1..3).map {
                PendingEvidenceProposal(
                    target.copy(id = UUID(0, it.toLong())),
                    f.compiled.extraction,
                    f.actor,
                    now,
                    "Recovery",
                )
            }
        Mockito.`when`(repository.pending(f.tenant, null, 3)).thenReturn(entries)
        val page = service.pending(f.tenant, f.actor, null, 2)
        assertThat(page.entries).containsExactlyElementsOf(entries.take(2))
        assertThat(page.next).isEqualTo(EvidenceReviewCursor(now, target.kind, entries[1].target.id))
        for (limit in listOf(0, 51)) {
            assertThatThrownBy { service.pending(f.tenant, f.actor, null, limit) }
                .isInstanceOf(InvalidEvidenceReview::class.java)
        }
    }

    @Test
    fun `invalid rationale and cursor cannot enter the contract`() {
        for (rationale in listOf("", " ", " padded", "bad\u0000", "x".repeat(2001), "\uD800")) {
            assertThatThrownBy { command.copy(rationale = rationale) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(command.copy(rationale = "Unicode 😀\n\ttext").rationale).contains("😀")
        assertThatThrownBy { command.copy(expectedVersion = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EvidenceReviewCursor(now.plusNanos(1), target.kind, target.id) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun allow(id: UUID = UUID(0, 40)): StoredApprovalAuthorityEvidence {
        val proof =
            StoredApprovalAuthorityEvidence(
                ApprovalAuthorityEvidence(
                    ApprovalAuthorityEvidenceId(id),
                    f.actor,
                    ApprovalAuthority.RESOLVER,
                    null,
                    ApprovalAuthorityEvidenceSource.create("test", "resolvers"),
                    now.minusSeconds(60),
                    now.plusSeconds(60),
                ),
                now,
            )
        Mockito
            .`when`(authorities.findCurrent(f.tenant, f.actor, ApprovalAuthority.RESOLVER, null, now))
            .thenReturn(proof)
        return proof
    }
}
