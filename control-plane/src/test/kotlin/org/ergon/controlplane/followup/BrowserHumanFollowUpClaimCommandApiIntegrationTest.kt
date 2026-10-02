package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommand
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommandConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommandRecording
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpOwnershipRevisionConflictException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpClaimCommandException
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaimCommand
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.adapter.inbound.http.BrowserCsrfTokenResponse
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimCommandId
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
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
class BrowserHumanFollowUpClaimCommandApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @MockitoBean
    private lateinit var claims: HumanFollowUpClaimService

    @Test
    fun `requires session CSRF and a tenant-bound actor before invoking the command`() {
        val tenantId = UUID.randomUUID()
        val workItemId = UUID.randomUUID()
        val body = commandBody(UUID.randomUUID(), 0)

        mockMvc
            .perform(post(COMMAND_PATH, tenantId, workItemId).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
        mockMvc
            .perform(
                post(COMMAND_PATH, tenantId, workItemId)
                    .with(verifiedSession())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        Mockito.verifyNoInteractions(claims)

        registerActor(UUID.randomUUID())
        mockMvc
            .perform(commandRequest(tenantId, workItemId, UUID.randomUUID(), 0))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
        Mockito.verifyNoInteractions(claims)
    }

    @Test
    fun `new command and exact replay return only browser-safe claim data`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        val commandId = UUID.randomUUID()
        val command = HumanFollowUpClaimCommand(tenantId, workItemId, actorId, commandId, 0)
        val receipt = receipt(commandId, storedClaim(actorId, workItemId))
        val claimId = receipt.storedClaim.claim.id.value
        Mockito
            .`when`(claims.claimWithCommand(command))
            .thenReturn(HumanFollowUpClaimCommandRecording(receipt, created = true))
            .thenReturn(HumanFollowUpClaimCommandRecording(receipt, created = false))

        val created =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, commandId, 0))
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.commandId").value(commandId.toString()))
                .andExpect(jsonPath("$.ownershipRevision").value(1))
                .andExpect(jsonPath("$.claim.claimId").value(claimId.toString()))
                .andExpect(jsonPath("$.claim.workItemId").value(workItemId.toString()))
                .andExpect(jsonPath("$.claim.claimedAt").value(CLAIMED_AT.toString()))
                .andExpect(jsonPath("$.claim.recordedAt").value(RECORDED_AT.toString()))
                .andExpect(jsonPath("$.claim.resolverActorId").doesNotExist())
                .andExpect(jsonPath("$.claim.authorityEvidenceId").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn()
        val replayed =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, commandId, 0))
                .andExpect(status().isOk)
                .andReturn()
        assertThat(replayed.response.contentAsString)
            .isEqualTo(created.response.contentAsString)
        Mockito.verify(claims, Mockito.times(2)).claimWithCommand(command)
    }

    @Test
    fun `command conflicts and invalid revision keep stable non-disclosing problems`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItemId = UUID.randomUUID()
        val commandId = UUID.randomUUID()
        val command = HumanFollowUpClaimCommand(tenantId, workItemId, actorId, commandId, 0)

        Mockito
            .`when`(claims.claimWithCommand(command))
            .thenThrow(HumanFollowUpClaimCommandConflictException())
            .thenThrow(HumanFollowUpOwnershipRevisionConflictException())
            .thenThrow(InvalidHumanFollowUpClaimCommandException())
        mockMvc
            .perform(commandRequest(tenantId, workItemId, commandId, 0))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-claim-command-conflict"))
            .andExpect(jsonPath("$.resolverActorId").doesNotExist())
        mockMvc
            .perform(commandRequest(tenantId, workItemId, commandId, 0))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-ownership-revision-conflict"))
        mockMvc
            .perform(commandRequest(tenantId, workItemId, commandId, 0))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-claim-command"))
    }

    private fun commandRequest(
        tenantId: UUID,
        workItemId: UUID,
        commandId: UUID,
        expectedRevision: Long,
    ): MockHttpServletRequestBuilder {
        val tokenResult = mockMvc.perform(get(CSRF_PATH).with(verifiedSession())).andExpect(status().isOk).andReturn()
        val token =
            objectMapper.readValue(
                tokenResult.response.contentAsByteArray,
                BrowserCsrfTokenResponse::class.java,
            )
        val session = tokenResult.request.getSession(false) as MockHttpSession
        return post(COMMAND_PATH, tenantId, workItemId)
            .session(session)
            .with(verifiedSession())
            .header(token.headerName, token.token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(commandBody(commandId, expectedRevision))
    }

    private fun commandBody(
        commandId: UUID,
        expectedRevision: Long,
    ): String = """{"commandId":"$commandId","expectedOwnershipRevision":$expectedRevision}"""

    private fun verifiedSession() =
        oidcLogin().idToken {
            it.issuer(TRUSTED_ISSUER)
            it.subject(SUBJECT)
        }

    private fun registerActor(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(post(ACTORS_PATH, tenantId).contentType(MediaType.APPLICATION_JSON).content(ACTOR_REQUEST))
                .andExpect(status().isCreated)
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

    private fun receipt(
        commandId: UUID,
        claim: StoredHumanFollowUpClaim,
    ) = StoredHumanFollowUpClaimCommand(
        HumanFollowUpClaimCommandId(commandId),
        HumanFollowUpOwnershipRevision(0),
        HumanFollowUpOwnershipRevision(1),
        claim,
    )

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val COMMAND_PATH = "/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claim-commands"
        private const val CSRF_PATH = "/bff/v1/csrf"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val ACTOR_REQUEST = """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
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
