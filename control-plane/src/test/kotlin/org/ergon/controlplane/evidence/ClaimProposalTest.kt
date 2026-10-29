package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.inbound.http.BrowserClaimProposalController
import org.ergon.controlplane.evidence.adapter.inbound.http.ClaimProposalInput
import org.ergon.controlplane.evidence.application.ClaimProposalCommand
import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.ClaimProposalService
import org.ergon.controlplane.evidence.application.ClaimRegistration
import org.ergon.controlplane.evidence.application.InvalidClaim
import org.ergon.controlplane.evidence.application.StoredClaimProposal
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.ergon.controlplane.knowledge.adapter.inbound.document.CommonMarkSourceParser
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.ergon.controlplane.knowledge.application.SourceTooLarge
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.controlplane.knowledge.application.StoredSourceRevision
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
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
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class ClaimProposalTest {
    private val tenant = TenantId(UUID(0, 1))
    private val sourceId = SourceId(UUID(0, 2))
    private val format = EvidenceSourceFormat.COMMONMARK
    private val source = EvidenceSource.fromUtf8(tenant, sourceId, format, "# Supported 😀".toByteArray())
    private val actor = HumanActorId(UUID(0, 3))
    private val claim = DocumentaryClaim(DocumentaryProperty.SUPPORT, DocumentaryValue.Support(true), "sso", "staff")

    @Test
    fun `typed vocabulary rejects unknown mixed and mismatched values`() {
        val property = DocumentaryProperty.SUPPORT.key
        val input = ClaimProposalInput(UUID(0, 4), 0, property, "sso", "employees", booleanValue = true)
        assertThat(input.claim().value).isEqualTo(DocumentaryValue.Support(true))
        val textProperty = DocumentaryProperty.REQUIREMENT.key
        val requirement = input.copy(property = textProperty, booleanValue = null, textValue = "Use MFA")
        assertThat(requirement.claim().value).isEqualTo(DocumentaryValue.Requirement("Use MFA"))
        for (invalid in listOf(
            input.copy(property = "account.access.state"),
            input.copy(textValue = "true"),
            input.copy(booleanValue = null, textValue = "true"),
            input.copy(booleanValue = null),
            input.copy(scope = " "),
        )) {
            assertThatThrownBy { invalid.claim() }.isInstanceOf(InvalidClaim::class.java)
        }
        assertThatThrownBy { DocumentaryValue.Requirement("x".repeat(1001)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `source authorization and exact extraction selection precede proposal writes`() {
        val sources = Mockito.mock(SourceRevisionService::class.java)
        val compiler = Mockito.mock(SourceCompilationService::class.java)
        val repository = Mockito.mock(ClaimProposalRepository::class.java)
        val service = ClaimProposalService(sources, compiler, repository)
        val compiled = CommonMarkSourceParser().parse(source)
        val command = ClaimProposalCommand(UUID(0, 4), compiled.extraction.id, 0, claim)
        Mockito.`when`(compiler.compile(source.revision, actor)).thenThrow(SourceUnavailable())
        assertThatThrownBy { service.propose(source.revision, actor, command) }
            .isInstanceOf(SourceUnavailable::class.java)
        Mockito.doReturn(compiled).`when`(compiler).compile(source.revision, actor)
        val selections = listOf(UUID(0, 5) to 0, compiled.extraction.id to -1, compiled.extraction.id to 1)
        for ((extraction, index) in selections) {
            val invalid = command.copy(extractionId = extraction, observationIndex = index)
            assertThatThrownBy { service.propose(source.revision, actor, invalid) }
                .isInstanceOf(InvalidClaim::class.java)
        }
        Mockito.verifyNoInteractions(repository)
        val original = StoredSourceRevision(source, actor, Instant.EPOCH)
        Mockito.`when`(sources.get(source.revision, actor)).thenReturn(original)
        val expected =
            org.ergon.evidence.domain.ClaimProposal(
                UUID(0, 4),
                compiled.extraction,
                0,
                compiled.observations.single().citation,
                claim,
            )
        Mockito
            .`when`(repository.register(expected, actor))
            .thenReturn(ClaimRegistration(StoredClaimProposal(expected, actor, Instant.EPOCH), true))
        service.propose(source.revision, actor, command)
        Mockito.verify(repository).register(expected, actor)
        verifyRead(service, repository, StoredClaimProposal(expected, actor, Instant.EPOCH))
    }

    @Test
    fun `HTTP commands reject coerced booleans missing selection unknown quotations and oversize bodies`() {
        val actors = Mockito.mock(AuthenticatedHumanActorResolver::class.java)
        val claims = Mockito.mock(ClaimProposalService::class.java)
        val principal = Mockito.mock(OidcUser::class.java)
        val tenantId = source.revision.tenantId.value
        val compiled = CommonMarkSourceParser().parse(source)
        val proposal =
            org.ergon.evidence.domain.ClaimProposal(
                UUID(0, 4),
                compiled.extraction,
                0,
                compiled.observations.single().citation,
                claim,
            )
        val command = ClaimProposalCommand(proposal.id, compiled.extraction.id, 0, claim)
        Mockito.`when`(actors.resolve(tenantId, principal)).thenReturn(
            StoredHumanActor(HumanActor.create(actor, "test", "subject"), Instant.EPOCH, Instant.EPOCH),
        )
        Mockito.`when`(claims.propose(source.revision, actor, command)).thenReturn(
            ClaimRegistration(StoredClaimProposal(proposal, actor, Instant.EPOCH), true),
        )
        val controller = BrowserClaimProposalController(actors, claims, JsonMapper.builder().build())
        val path =
            mapOf(
                "tenantId" to tenantId.toString(),
                "sourceId" to
                    source.revision.sourceId.value
                        .toString(),
                "digest" to source.revision.digest.value,
                "id" to proposal.id.toString(),
            )
        val json =
            """
            {"extractionId":"${compiled.extraction.id}","observationIndex":0,
             "property":"${claim.property.key}","subject":"${claim.subject}",
             "scope":"${claim.scope}","booleanValue":true}
            """.trimIndent()
        val response = controller.propose(path, principal, request(json))
        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(response.headers.cacheControl).isEqualTo("no-store")
        assertThat(response.body?.status).isEqualTo("PROPOSED")
        assertThat(response.body?.quote).isEqualTo(source.text)
        Mockito.clearInvocations(claims)
        for (invalid in listOf(
            json.replace(":true", ":\"true\""),
            json.replace("\"observationIndex\":0", "\"observationIndex\":null"),
            json.replace("\"observationIndex\":0", "\"observationIndex\":0.5"),
            json.replace("\"booleanValue\":true", "\"textValue\":true"),
            json.replace("\"observationIndex\":0,", ""),
            json.dropLast(1) + ",\"quote\":\"forged\"}",
        )) {
            assertThatThrownBy { controller.propose(path, principal, request(invalid)) }
                .isInstanceOf(InvalidClaim::class.java)
        }
        assertThatThrownBy { controller.propose(path, principal, request("x".repeat(8193))) }
            .isInstanceOf(SourceTooLarge::class.java)
        Mockito.verifyNoInteractions(claims)
    }

    private fun request(json: String): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            contentType = "application/json"
            setContent(json.toByteArray(Charsets.UTF_8))
        }

    private fun verifyRead(
        service: ClaimProposalService,
        repository: ClaimProposalRepository,
        stored: StoredClaimProposal,
    ) {
        Mockito.`when`(repository.find(source.revision.tenantId, stored.proposal.id)).thenReturn(stored)
        assertThat(service.get(source.revision, actor, stored.proposal.id)).isEqualTo(stored)
        val citation = stored.proposal.citation.copy(quote = "forged")
        val corrupted = stored.copy(proposal = stored.proposal.copy(citation = citation))
        Mockito.`when`(repository.find(source.revision.tenantId, stored.proposal.id)).thenReturn(corrupted)
        assertThatThrownBy { service.get(source.revision, actor, stored.proposal.id) }
            .isInstanceOf(IllegalStateException::class.java)
        Mockito.`when`(repository.find(source.revision.tenantId, stored.proposal.id)).thenReturn(null)
        assertThatThrownBy { service.get(source.revision, actor, stored.proposal.id) }
            .isInstanceOf(SourceUnavailable::class.java)
    }
}
