package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.inbound.http.BrowserContradictionController
import org.ergon.controlplane.evidence.adapter.inbound.http.ContradictionInput
import org.ergon.controlplane.evidence.application.ContradictionAdjudicationView
import org.ergon.controlplane.evidence.application.ContradictionConflict
import org.ergon.controlplane.evidence.application.ContradictionRegistration
import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.evidence.application.ContradictionSetTooLarge
import org.ergon.controlplane.evidence.application.InvalidContradiction
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.identity.domain.HumanActor
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import tools.jackson.databind.json.JsonMapper

class BrowserContradictionTest {
    private val f = ContradictionFixtures()
    private val actors = Mockito.mock(AuthenticatedHumanActorResolver::class.java)
    private val service = Mockito.mock(ContradictionService::class.java)
    private val principal = Mockito.mock(OidcUser::class.java)
    private val mapper = JsonMapper.builder().build()
    private val controller = BrowserContradictionController(actors, service, mapper)
    private val path =
        mapOf(
            "tenantId" to
                f.source.tenant.value
                    .toString(),
            "id" to f.command.id.toString(),
            "anchorId" to f.command.anchorId.toString(),
        )

    @Test
    fun `assessment and adjudication expose exact accepted citations current state and private receipts`() {
        allowActor()
        Mockito.`when`(service.assess(f.source.tenant, f.source.actor, f.command.anchorId)).thenReturn(f.assessed)
        val response = controller.assess(path, principal)
        assertThat(response.headers.cacheControl).isEqualTo("no-store")
        assertThat(response.body?.state).isEqualTo("CONFLICT")
        assertThat(
            response.body
                ?.candidates
                ?.first()
                ?.claim
                ?.status,
        ).isEqualTo("ACCEPTED")
        assertThat(
            response.body
                ?.candidates
                ?.first()
                ?.claim
                ?.quote,
        ).isEqualTo(
            f.candidates
                .first()
                .stored.proposal.citation.quote,
        )
        val view = ContradictionAdjudicationView(f.stored(), f.assessed, true)
        Mockito
            .`when`(service.adjudicate(f.source.tenant, f.source.actor, f.command))
            .thenReturn(ContradictionRegistration(view, true))
        val created = controller.adjudicate(path, principal, request(json()))
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(created.headers.cacheControl).isEqualTo("no-store")
        assertThat(created.body?.reviewerId).isEqualTo(f.source.actor.value)
        Mockito
            .`when`(service.adjudicate(f.source.tenant, f.source.actor, f.command))
            .thenReturn(ContradictionRegistration(view.copy(current = false), false))
        val retry = controller.adjudicate(path, principal, request(json()))
        assertThat(retry.statusCode.value()).isEqualTo(200)
        assertThat(retry.body?.current).isFalse()
        Mockito.`when`(service.get(f.source.tenant, f.source.actor, f.command.id)).thenReturn(view)
        assertThat(controller.get(path, principal).body).isEqualTo(created.body)
    }

    @Test
    fun `strict bounded JSON rejects fabricated authority altered shapes coercion and trailing input`() {
        allowActor()
        val json = json()
        for (invalid in listOf(
            json.replace("\"expectedRevision\":2", "\"expectedRevision\":null"),
            json.replace("\"expectedRevision\":2", "\"expectedRevision\":2.5"),
            json.replace("\"expectedRevision\":2", "\"expectedRevision\":\"2\""),
            json.replace("\"expectedRevision\":2,", ""),
            json.replace("\"choice\":\"PREFER_CLAIM\"", "\"choice\":true"),
            json.replace("\"PREFER_CLAIM\"", "\"ACCEPTED\""),
            json.replace("\"${f.command.candidateIds.first()}\"", "null"),
            json.dropLast(1) + ",\"reviewerId\":\"fake\"}",
            json.dropLast(1) + ",\"rationale\":\"duplicate\"}",
            json + " {}",
            "{broken",
        )) {
            assertThatThrownBy { controller.adjudicate(path, principal, request(invalid)) }
                .isInstanceOf(InvalidContradiction::class.java)
        }
        assertThatThrownBy { controller.adjudicate(path, principal, request("x".repeat(16_385))) }
            .isInstanceOf(SourceTooLarge::class.java)
        Mockito.verifyNoInteractions(service)
        assertThat(controller.failure(ContradictionConflict()).statusCode.value()).isEqualTo(409)
        assertThat(controller.failure(SourceUnavailable()).statusCode.value()).isEqualTo(404)
        assertThat(controller.failure(ContradictionSetTooLarge()).statusCode.value()).isEqualTo(422)
        assertThat(controller.failure(InvalidContradiction()).headers.cacheControl).isEqualTo("no-store")
    }

    private fun json(): String =
        mapper.writeValueAsString(
            ContradictionInput(
                f.command.anchorId,
                f.command.expectedRevision,
                f.command.candidateIds,
                f.command.choice.name,
                f.command.rationale,
                f.command.selectedClaimId,
            ),
        )

    private fun allowActor() {
        Mockito.`when`(actors.resolve(f.source.tenant.value, principal)).thenReturn(
            StoredHumanActor(HumanActor.create(f.source.actor, "test", "subject"), f.now, f.now),
        )
    }

    private fun request(json: String) =
        MockHttpServletRequest().apply {
            contentType = "application/json"
            setContent(json.toByteArray(Charsets.UTF_8))
        }
}
