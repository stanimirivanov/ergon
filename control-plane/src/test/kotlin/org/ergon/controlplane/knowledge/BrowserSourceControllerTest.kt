package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkSourceParser
import org.ergon.controlplane.knowledge.adapter.inbound.http.BrowserSourceController
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRegistration
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.identity.domain.HumanActor
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import java.time.Instant
import java.util.UUID

class BrowserSourceControllerTest {
    @Test
    fun `registration bounds reads and returns original attribution with no store`() {
        val tenant = TenantId(UUID(0, 1))
        val actor = HumanActorId(UUID(0, 2))
        val source =
            EvidenceSource.fromUtf8(
                tenant,
                SourceId(UUID(0, 3)),
                EvidenceSourceFormat.COMMONMARK,
                "# Heading".toByteArray(),
            )
        val now = Instant.parse("2026-10-29T12:00:00Z")
        val actors = Mockito.mock(AuthenticatedHumanActorResolver::class.java)
        val service = Mockito.mock(SourceRevisionService::class.java)
        val principal = Mockito.mock(OidcUser::class.java)
        val compilation = Mockito.mock(SourceCompilationService::class.java)
        val controller = BrowserSourceController(actors, service, compilation)
        Mockito
            .`when`(actors.resolve(tenant.value, principal))
            .thenReturn(StoredHumanActor(HumanActor.create(actor, "test", "subject"), now, now))
        val bounded = ByteArray(SourceRevisionService.MAX_BYTES + 1)
        Mockito
            .`when`(service.register(source.revision, actor, source.format, bounded))
            .thenReturn(SourceRegistration(StoredSourceRevision(source, actor, now), false))
        val request = MockHttpServletRequest()
        request.contentType = "text/markdown"
        request.setContent(ByteArray(SourceRevisionService.MAX_BYTES + 100))
        val response =
            controller.register(
                tenant.value,
                source.revision.sourceId.value,
                source.revision.digest.value,
                principal,
                request,
            )
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.headers.cacheControl).isEqualTo("no-store")
        assertThat(response.body?.submittedBy).isEqualTo(actor.value)
        assertThat(response.body?.recordedAt).isEqualTo(now)
        assertThat(request.inputStream.available()).isEqualTo(99)
        Mockito.verify(service).requireAccess(source.revision, actor)
        Mockito.`when`(compilation.compile(source.revision, actor)).thenReturn(CommonMarkSourceParser().parse(source))
        val compiled =
            controller.compile(
                tenant.value,
                source.revision.sourceId.value,
                source.revision.digest.value,
                principal,
            )
        assertThat(compiled.headers.cacheControl).isEqualTo("no-store")
        assertThat(compiled.body?.sourceId).isEqualTo(source.revision.sourceId.value)
        assertThat(
            compiled.body
                ?.observations
                ?.single()
                ?.quote,
        ).isEqualTo("# Heading")
    }
}
