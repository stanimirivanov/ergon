package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.followup.application.HumanFollowUpOwnershipRevisionConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseCommand
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseRecording
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseUnavailableException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpReleaseCommandException
import org.ergon.controlplane.followup.application.StoredHumanFollowUpRelease
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.adapter.inbound.http.BrowserCsrfTokenResponse
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class)
class BrowserHumanFollowUpReleaseApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @MockitoBean
    private lateinit var releases: HumanFollowUpReleaseService

    @Test
    fun `requires session CSRF and tenant actor before release application`() {
        val tenantId = UUID.randomUUID()
        val workItemId = UUID.randomUUID()
        val claimId = UUID.randomUUID()

        mockMvc
            .perform(
                post(RELEASE_PATH, tenantId, workItemId, claimId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
        mockMvc
            .perform(
                post(RELEASE_PATH, tenantId, workItemId, claimId)
                    .with(verifiedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        Mockito.verifyNoInteractions(releases)

        registerActor(UUID.randomUUID())
        mockMvc
            .perform(releaseRequest(tenantId, workItemId, claimId, 1))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
        Mockito.verifyNoInteractions(releases)
    }

    @Test
    fun `release and exact replay return only browser-safe receipt`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        val claimId = UUID.randomUUID()
        val command = HumanFollowUpReleaseCommand(tenantId, workItemId, claimId, actorId, 1)
        val receipt = releaseReceipt(workItemId, claimId, actorId)
        Mockito
            .`when`(releases.release(command))
            .thenReturn(HumanFollowUpReleaseRecording(receipt, created = true))
            .thenReturn(HumanFollowUpReleaseRecording(receipt, created = false))

        val created =
            mockMvc
                .perform(releaseRequest(tenantId, workItemId, claimId, 1))
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.claimId").value(claimId.toString()))
                .andExpect(jsonPath("$.workItemId").value(workItemId.toString()))
                .andExpect(jsonPath("$.ownershipRevision").value(2))
                .andExpect(jsonPath("$.releasedAt").value(RELEASED_AT.toString()))
                .andExpect(jsonPath("$.recordedAt").value(RECORDED_AT.toString()))
                .andExpect(jsonPath("$.resolverActorId").doesNotExist())
                .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn()
        val replayed =
            mockMvc
                .perform(releaseRequest(tenantId, workItemId, claimId, 1))
                .andExpect(status().isOk)
                .andReturn()
        assertThat(replayed.response.contentAsString).isEqualTo(created.response.contentAsString)
        Mockito.verify(releases, Mockito.times(2)).release(command)
    }

    @Test
    fun `release failures retain stable non-disclosing problem types`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        val claimId = UUID.randomUUID()
        val command = HumanFollowUpReleaseCommand(tenantId, workItemId, claimId, actorId, 1)
        Mockito
            .`when`(releases.release(command))
            .thenThrow(HumanFollowUpReleaseNotFoundException())
            .thenThrow(HumanFollowUpOwnershipRevisionConflictException())
            .thenThrow(HumanFollowUpReleaseUnavailableException())
            .thenThrow(InvalidHumanFollowUpReleaseCommandException())

        listOf(
            404 to "human-follow-up-release-not-found",
            409 to "human-follow-up-ownership-revision-conflict",
            503 to "human-follow-up-release-unavailable",
            400 to "invalid-human-follow-up-release-command",
        ).forEach { (statusCode, type) ->
            mockMvc
                .perform(releaseRequest(tenantId, workItemId, claimId, 1))
                .andExpect(status().`is`(statusCode))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:$type"))
                .andExpect(jsonPath("$.resolverActorId").doesNotExist())
        }
    }

    private fun releaseRequest(
        tenantId: UUID,
        workItemId: UUID,
        claimId: UUID,
        expectedRevision: Long,
    ): MockHttpServletRequestBuilder {
        val tokenResult = mockMvc.perform(get(CSRF_PATH).with(verifiedSession())).andExpect(status().isOk).andReturn()
        val token =
            objectMapper.readValue(
                tokenResult.response.contentAsByteArray,
                BrowserCsrfTokenResponse::class.java,
            )
        val session = tokenResult.request.getSession(false) as MockHttpSession
        return post(RELEASE_PATH, tenantId, workItemId, claimId)
            .session(session)
            .with(verifiedSession())
            .header(token.headerName, token.token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"expectedOwnershipRevision":$expectedRevision}""")
    }

    private fun registerActor(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(post(ACTORS_PATH, tenantId).contentType(MediaType.APPLICATION_JSON).content(ACTOR_REQUEST))
                .andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun verifiedSession() =
        oidcLogin().idToken {
            it.issuer(TRUSTED_ISSUER)
            it.subject(SUBJECT)
        }

    private fun releaseReceipt(
        workItemId: UUID,
        claimId: UUID,
        actorId: UUID,
    ) = StoredHumanFollowUpRelease(
        HumanFollowUpWorkItemId(workItemId),
        HumanFollowUpClaimId(claimId),
        HumanActorId(actorId),
        ApprovalAuthorityEvidenceId(UUID.randomUUID()),
        HumanFollowUpOwnershipRevision(2),
        RELEASED_AT,
        RECORDED_AT,
    )

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val RELEASE_PATH =
            "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/{claimId}/release"
        private const val CSRF_PATH = "/bff/v1/csrf"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val ACTOR_REQUEST = """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
        private const val BODY = """{"expectedOwnershipRevision":1}"""
        private val RELEASED_AT = Instant.parse("2026-10-02T10:15:00Z")
        private val RECORDED_AT = Instant.parse("2026-10-02T10:15:01Z")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
