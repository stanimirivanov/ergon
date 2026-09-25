package org.ergon.controlplane.followup

import org.ergon.controlplane.followup.application.CurrentHumanFollowUpResolverAuthorityNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpAlreadyClaimedException
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRecording
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemNotFoundException
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.adapter.inbound.http.BrowserCsrfTokenResponse
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
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
class BrowserHumanFollowUpClaimApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @MockitoBean
    private lateinit var claims: HumanFollowUpClaimService

    @Test
    fun `returns a session-bound CSRF token without caching it`() {
        mockMvc
            .perform(get(CSRF_PATH).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty)
    }

    @Test
    fun `requires authentication before reporting a missing CSRF token`() {
        mockMvc
            .perform(post(CLAIMS_PATH, UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
            .andExpect(jsonPath("$.signInPath").value("/bff/login"))

        Mockito.verifyNoInteractions(claims)
    }

    @Test
    fun `rejects an authenticated mutation without the session CSRF token`() {
        mockMvc
            .perform(post(CLAIMS_PATH, UUID.randomUUID(), UUID.randomUUID()).with(verifiedSession()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))

        Mockito.verifyNoInteractions(claims)
    }

    @Test
    fun `claims work using the token issued for the browser session`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        val stored = storedClaim(actorId, workItemId)
        Mockito
            .`when`(claims.claim(tenantId, workItemId, actorId))
            .thenReturn(HumanFollowUpClaimRecording(stored, created = true))

        mockMvc
            .perform(claimRequest(tenantId, workItemId))
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(
                jsonPath("$.claimId").value(
                    stored.claim.id.value
                        .toString(),
                ),
            ).andExpect(jsonPath("$.workItemId").value(workItemId.toString()))
            .andExpect(jsonPath("$.claimedAt").value(CLAIMED_AT.toString()))
            .andExpect(jsonPath("$.recordedAt").value(RECORDED_AT.toString()))
            .andExpect(jsonPath("$.resolverActorId").doesNotExist())
            .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())

        Mockito.verify(claims).claim(tenantId, workItemId, actorId)
    }

    @Test
    fun `replays the same resolver claim as an existing result`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        val stored = storedClaim(actorId, workItemId)
        Mockito
            .`when`(claims.claim(tenantId, workItemId, actorId))
            .thenReturn(HumanFollowUpClaimRecording(stored, created = false))

        mockMvc.perform(claimRequest(tenantId, workItemId)).andExpect(status().isOk)
    }

    @Test
    fun `does not accept an actor binding from another tenant`() {
        registerActor(UUID.randomUUID())

        mockMvc
            .perform(claimRequest(UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))

        Mockito.verifyNoInteractions(claims)
    }

    @Test
    fun `preserves stable conflicts and non-disclosing claim failures`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        Mockito
            .`when`(claims.claim(tenantId, workItemId, actorId))
            .thenThrow(HumanFollowUpAlreadyClaimedException(workItemId))

        mockMvc
            .perform(claimRequest(tenantId, workItemId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-already-claimed"))

        Mockito.reset(claims)
        Mockito
            .`when`(claims.claim(tenantId, workItemId, actorId))
            .thenThrow(CurrentHumanFollowUpResolverAuthorityNotFoundException())

        mockMvc
            .perform(claimRequest(tenantId, workItemId))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-resolver-authority-required"))

        Mockito.reset(claims)
        Mockito
            .`when`(claims.claim(tenantId, workItemId, actorId))
            .thenThrow(HumanFollowUpWorkItemNotFoundException(workItemId))

        mockMvc
            .perform(claimRequest(tenantId, workItemId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-work-item-not-found"))
    }

    private fun claimRequest(
        tenantId: UUID,
        workItemId: UUID,
    ): MockHttpServletRequestBuilder {
        val tokenResult =
            mockMvc
                .perform(get(CSRF_PATH).with(verifiedSession()))
                .andExpect(status().isOk)
                .andReturn()
        val token =
            objectMapper.readValue(
                tokenResult.response.contentAsByteArray,
                BrowserCsrfTokenResponse::class.java,
            )
        val session = tokenResult.request.getSession(false) as MockHttpSession
        return post(CLAIMS_PATH, tenantId, workItemId)
            .session(session)
            .with(verifiedSession())
            .header(token.headerName, token.token)
    }

    private fun verifiedSession() =
        oidcLogin().idToken {
            it.issuer(TRUSTED_ISSUER)
            it.subject(SUBJECT)
        }

    private fun registerActor(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(
                    post(ACTORS_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTOR_REQUEST),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun storedClaim(
        actorId: UUID,
        workItemId: UUID,
    ): StoredHumanFollowUpClaim {
        val evidence =
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                HumanActorId(actorId),
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
                CLAIMED_AT.minusSeconds(60),
                CLAIMED_AT.plusSeconds(60),
            )
        return StoredHumanFollowUpClaim(
            HumanFollowUpClaim.claim(
                HumanFollowUpClaimId(UUID.randomUUID()),
                HumanFollowUpWorkItemId(workItemId),
                evidence,
                CLAIMED_AT,
            ),
            RECORDED_AT,
        )
    }

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val CLAIMS_PATH = "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims"
        private const val CSRF_PATH = "/bff/v1/csrf"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val ACTOR_REQUEST =
            """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
        private val CLAIMED_AT = Instant.parse("2026-09-22T10:15:00Z")
        private val RECORDED_AT = Instant.parse("2026-09-22T10:15:01Z")

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
