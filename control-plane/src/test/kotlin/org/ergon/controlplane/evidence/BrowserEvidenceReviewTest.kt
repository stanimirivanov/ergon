package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.inbound.http.BrowserEvidenceReviewController
import org.ergon.controlplane.evidence.application.EvidenceReviewConflict
import org.ergon.controlplane.evidence.application.EvidenceReviewInspection
import org.ergon.controlplane.evidence.application.EvidenceReviewPage
import org.ergon.controlplane.evidence.application.EvidenceReviewReceipt
import org.ergon.controlplane.evidence.application.EvidenceReviewRegistration
import org.ergon.controlplane.evidence.application.EvidenceReviewService
import org.ergon.controlplane.evidence.application.InvalidEvidenceReview
import org.ergon.controlplane.evidence.application.ReviewableEvidenceProposal
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.identity.domain.HumanActor
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class BrowserEvidenceReviewTest {
    private val f = ProcedureFixtures()
    private val actors = Mockito.mock(AuthenticatedHumanActorResolver::class.java)
    private val reviews = Mockito.mock(EvidenceReviewService::class.java)
    private val principal = Mockito.mock(OidcUser::class.java)
    private val controller = BrowserEvidenceReviewController(actors, reviews, JsonMapper.builder().build())
    private val target = EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, f.command.id)
    private val command =
        EvidenceReviewCommand(
            UUID(0, 30),
            f.compiled.extraction.id,
            0,
            EvidenceReviewDecision.ACCEPTED,
            "Verified",
        )
    private val path =
        mapOf(
            "tenantId" to f.tenant.value.toString(),
            "kind" to "PROCEDURE",
            "id" to target.id.toString(),
            "commandId" to command.id.toString(),
        )

    @Test
    fun `review receipt and inspection carry authoritative status with no store headers`() {
        allowActor()
        val receipt =
            EvidenceReviewReceipt(
                target,
                command,
                f.actor,
                ApprovalAuthorityEvidenceId(UUID(0, 40)),
                Instant.EPOCH,
            )
        Mockito
            .`when`(reviews.decide(f.tenant, f.actor, target, command))
            .thenReturn(EvidenceReviewRegistration(receipt, true))
        val created = controller.decide(path, principal, request(json()))
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(created.headers.cacheControl).isEqualTo("no-store")
        assertThat(created.body?.decision).isEqualTo("ACCEPTED")
        Mockito
            .`when`(reviews.decide(f.tenant, f.actor, target, command))
            .thenReturn(EvidenceReviewRegistration(receipt, false))
        assertThat(controller.decide(path, principal, request(json())).statusCode.value()).isEqualTo(200)
        val proposal =
            ReviewableEvidenceProposal.Procedure(StoredProcedureProposal(f.proposal(), f.actor, Instant.EPOCH))
        Mockito
            .`when`(reviews.inspect(f.tenant, f.actor, target))
            .thenReturn(EvidenceReviewInspection(proposal, receipt))
        val inspection = controller.inspect(path, principal)
        assertThat(inspection.body?.version).isEqualTo(1)
        assertThat(inspection.body?.procedure?.status).isEqualTo("ACCEPTED")
        assertThat(inspection.body?.procedure?.steps).hasSize(2)
    }

    @Test
    fun `strict bounded review input excludes coercion duplicate fields fabricated identity and trailing data`() {
        allowActor()
        for (bad in listOf(
            json().replace("\"expectedVersion\":0", "\"expectedVersion\":null"),
            json().replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
            json().replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            json().replace("\"expectedVersion\":0,", ""),
            json().replace("\"rationale\":\"Verified\"", "\"rationale\":true"),
            json().dropLast(1) + ",\"reviewerId\":\"fake\"}",
            json().dropLast(1) + ",\"decision\":\"REJECTED\"}",
            json().replace("ACCEPTED", "PROPOSED"),
            json() + " {}",
            "{broken",
        )) {
            assertThatThrownBy { controller.decide(path, principal, request(bad)) }
                .isInstanceOf(InvalidEvidenceReview::class.java)
        }
        assertThatThrownBy { controller.decide(path, principal, request("x".repeat(16_385))) }
            .isInstanceOf(SourceTooLarge::class.java)
        Mockito.verifyNoInteractions(reviews)
        assertThat(controller.failure(SourceUnavailable()).statusCode.value()).isEqualTo(404)
        assertThat(controller.failure(EvidenceReviewConflict()).statusCode.value()).isEqualTo(409)
        assertThat(controller.failure(InvalidEvidenceReview()).headers.cacheControl).isEqualTo("no-store")
    }

    @Test
    fun `queue rejects partial malformed and unknown cursor parameters`() {
        allowActor()
        for (query in listOf(
            mapOf("beforeId" to target.id.toString()),
            mapOf("limit" to "no"),
            mapOf("offset" to "10"),
            mapOf("beforeRecordedAt" to "bad", "beforeKind" to "CLAIM", "beforeId" to "bad"),
        )) {
            assertThatThrownBy { controller.pending(path, query, principal) }
                .isInstanceOf(InvalidEvidenceReview::class.java)
        }
        Mockito.verifyNoInteractions(reviews)
        Mockito.`when`(reviews.pending(f.tenant, f.actor, null, 20)).thenReturn(EvidenceReviewPage(emptyList(), null))
        assertThat(controller.pending(path, emptyMap(), principal).headers.cacheControl).isEqualTo("no-store")
    }

    private fun allowActor() {
        Mockito.`when`(actors.resolve(f.tenant.value, principal)).thenReturn(
            StoredHumanActor(HumanActor.create(f.actor, "test", "subject"), Instant.EPOCH, Instant.EPOCH),
        )
    }

    private fun json(): String =
        """{"extractionId":"${command.extractionId}","expectedVersion":0,""" +
            """"decision":"ACCEPTED","rationale":"Verified"}"""

    private fun request(json: String) =
        MockHttpServletRequest().apply {
            contentType = "application/json"
            setContent(json.toByteArray(Charsets.UTF_8))
        }
}
