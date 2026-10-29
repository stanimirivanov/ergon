package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.inbound.http.BrowserProcedureProposalController
import org.ergon.controlplane.evidence.adapter.inbound.http.ProcedureInput
import org.ergon.controlplane.evidence.adapter.inbound.http.ProcedureOperationInput
import org.ergon.controlplane.evidence.application.InvalidProcedure
import org.ergon.controlplane.evidence.application.ProcedureConflict
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.evidence.application.ProcedureRegistration
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
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
import java.time.Instant

class BrowserProcedureProposalTest {
    private val f = ProcedureFixtures()
    private val actors = Mockito.mock(AuthenticatedHumanActorResolver::class.java)
    private val procedures = Mockito.mock(ProcedureProposalService::class.java)
    private val principal = Mockito.mock(OidcUser::class.java)
    private val mapper = JsonMapper.builder().build()
    private val controller = BrowserProcedureProposalController(actors, procedures, mapper)
    private val path =
        mapOf(
            "tenantId" to f.tenant.value.toString(),
            "sourceId" to
                f.markdown.revision.sourceId.value
                    .toString(),
            "digest" to f.markdown.revision.digest.value,
            "id" to f.command.id.toString(),
        )

    @Test
    fun `valid explicit API selection produces a private proposed response and exact read`() {
        allowActor()
        val stored = StoredProcedureProposal(f.proposal(), f.actor, Instant.EPOCH)
        Mockito
            .`when`(procedures.propose(f.markdown.revision, f.actor, f.command))
            .thenReturn(ProcedureRegistration(stored, true))
        Mockito.`when`(procedures.get(f.markdown.revision, f.actor, f.command.id)).thenReturn(stored)
        val created = controller.propose(path, principal, request(json()))
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(created.headers.cacheControl).isEqualTo("no-store")
        assertThat(created.body?.status).isEqualTo("PROPOSED")
        val steps = checkNotNull(created.body).steps
        assertThat(steps.map { it.position }).containsExactly(1, 2)
        assertThat(steps.first().operation?.jsonPointer).isEqualTo("/paths/~1account/post")
        assertThat(steps.last().operation).isNull()
        assertThat(controller.get(path, principal).body).isEqualTo(created.body)
        Mockito
            .`when`(procedures.propose(f.markdown.revision, f.actor, f.command))
            .thenReturn(ProcedureRegistration(stored, false))
        assertThat(controller.propose(path, principal, request(json())).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `strict bounded JSON rejects fabricated quotes null selections coercion and invalid reference identities`() {
        allowActor()
        val json = json()
        val missingSelection = "\"observationIndex\":${f.listIndex},"
        for (invalid in listOf(
            json.dropLast(1) + ",\"quote\":\"forged\"}",
            json.replace(missingSelection, ""),
            json.replace(missingSelection, "\"observationIndex\":null,"),
            json.replace(missingSelection, "\"observationIndex\":0.5,"),
            json.replace("\"Restore access\"", "true"),
            json.replace("\"position\":1", "\"position\":\"1\""),
            json.replace(f.api.revision.digest.value, "bad"),
            json + " {}",
            "{broken",
        )) {
            assertThatThrownBy { controller.propose(path, principal, request(invalid)) }
                .isInstanceOf(InvalidProcedure::class.java)
        }
        assertThatThrownBy { controller.propose(path, principal, request("x".repeat(16_385))) }
            .isInstanceOf(SourceTooLarge::class.java)
        Mockito.verifyNoInteractions(procedures)
        assertThat(controller.failure(SourceUnavailable()).statusCode.value()).isEqualTo(404)
        assertThat(controller.failure(ProcedureConflict()).statusCode.value()).isEqualTo(409)
        assertThat(controller.failure(InvalidProcedure()).headers.cacheControl).isEqualTo("no-store")
    }

    private fun allowActor() {
        Mockito
            .`when`(
                actors.resolve(f.tenant.value, principal),
            ).thenReturn(StoredHumanActor(HumanActor.create(f.actor, "test", "subject"), Instant.EPOCH, Instant.EPOCH))
    }

    private fun json(): String =
        mapper.writeValueAsString(
            ProcedureInput(
                f.compiled.extraction.id,
                f.listIndex,
                f.command.title,
                listOf(
                    ProcedureOperationInput(
                        1,
                        f.api.revision.sourceId.value,
                        f.api.revision.digest.value,
                        f.compiledApi.extraction.id,
                        f.operationIndex,
                    ),
                ),
            ),
        )

    private fun request(json: String): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            contentType = "application/json"
            setContent(json.toByteArray(Charsets.UTF_8))
        }
}
