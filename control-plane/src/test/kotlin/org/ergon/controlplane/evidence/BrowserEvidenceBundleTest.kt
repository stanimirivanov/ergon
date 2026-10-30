package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.inbound.http.BrowserEvidenceBundleController
import org.ergon.controlplane.evidence.adapter.inbound.http.BundleGroupInput
import org.ergon.controlplane.evidence.adapter.inbound.http.BundlePublicationInput
import org.ergon.controlplane.evidence.application.EvidenceBundleConflict
import org.ergon.controlplane.evidence.application.EvidenceBundleRegistration
import org.ergon.controlplane.evidence.application.EvidenceBundleService
import org.ergon.controlplane.evidence.application.InvalidEvidenceBundle
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

class BrowserEvidenceBundleTest {
    private val b = EvidenceBundleFixtures()
    private val actors = Mockito.mock(AuthenticatedHumanActorResolver::class.java)
    private val service = Mockito.mock(EvidenceBundleService::class.java)
    private val principal = Mockito.mock(OidcUser::class.java)
    private val mapper = JsonMapper.builder().build()
    private val controller = BrowserEvidenceBundleController(actors, service, b.codec, mapper)
    private val path =
        mapOf(
            "tenantId" to
                b.f.source.tenant.value
                    .toString(),
            "id" to b.command.id.toString(),
        )

    @Test
    fun `explicit publication and historical read return immutable manifests with private receipt headers`() {
        allow()
        Mockito
            .`when`(service.publish(b.f.source.tenant, b.f.source.actor, b.command))
            .thenReturn(EvidenceBundleRegistration(b.stored, true))
        val created = controller.publish(path, principal, request(json()))
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(created.headers.cacheControl).isEqualTo("no-store")
        assertThat(created.body?.manifest?.proposals).hasSize(3)
        assertThat(created.body?.manifest?.assessedAt).isEqualTo(b.f.now.toString())
        assertThat(created.body?.sha256).isEqualTo(b.stored.sha256)
        Mockito
            .`when`(service.publish(b.f.source.tenant, b.f.source.actor, b.command))
            .thenReturn(EvidenceBundleRegistration(b.stored, false))
        assertThat(controller.publish(path, principal, request(json())).statusCode.value()).isEqualTo(200)
        Mockito.`when`(service.get(b.f.source.tenant, b.f.source.actor, b.command.id)).thenReturn(b.stored)
        assertThat(controller.get(path, principal).body).isEqualTo(created.body)
        val canonical = controller.canonical(path, principal)
        assertThat(canonical.body).isEqualTo(b.codec.encode(b.f.source.tenant, b.manifest))
        assertThat(canonical.headers.eTag).contains(b.stored.sha256)
        assertThat(canonical.headers.cacheControl).isEqualTo("no-store")
    }

    @Test
    fun `strict bounded publication input rejects hints coercion duplicate keys missing sets and invalid selections`() {
        allow()
        val json = json()
        for (bad in listOf(
            json.replace("\"revision\":2", "\"revision\":null"),
            json.replace("\"revision\":2", "\"revision\":2.5"),
            json.replace("\"revision\":2", "\"revision\":\"2\""),
            json.replace("\"revision\":2,", ""),
            json.dropLast(1) + ",\"publishedBy\":\"forged\"}",
            json.dropLast(1) + ",\"claims\":[]}",
            json.replace("\"${b.command.claims.first()}\"", "null"),
            json + " {}",
            "{broken",
        )) {
            assertThatThrownBy { controller.publish(path, principal, request(bad)) }
                .isInstanceOf(InvalidEvidenceBundle::class.java)
        }
        assertThatThrownBy { controller.publish(path, principal, request("x".repeat(32_769))) }
            .isInstanceOf(SourceTooLarge::class.java)
        Mockito.verifyNoInteractions(service)
        assertThat(controller.failure(EvidenceBundleConflict()).statusCode.value()).isEqualTo(409)
        assertThat(controller.failure(SourceUnavailable()).statusCode.value()).isEqualTo(404)
        assertThat(controller.failure(InvalidEvidenceBundle()).statusCode.value()).isEqualTo(422)
    }

    private fun json(): String =
        mapper.writeValueAsString(
            BundlePublicationInput(
                b.command.claims,
                b.command.procedures,
                b.command.claimSets.map {
                    BundleGroupInput(it.anchorId, it.revision, it.candidateIds, it.adjudicationId)
                },
            ),
        )

    private fun allow() {
        Mockito.`when`(actors.resolve(b.f.source.tenant.value, principal)).thenReturn(
            StoredHumanActor(HumanActor.create(b.f.source.actor, "test", "subject"), b.f.now, b.f.now),
        )
    }

    private fun request(json: String) =
        MockHttpServletRequest().apply {
            contentType = "application/json"
            setContent(json.toByteArray(Charsets.UTF_8))
        }
}
