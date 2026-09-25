package org.ergon.controlplane.followup

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.InvalidResolverOwnedHumanFollowUpPageException
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpCursor
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpPage
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpWork
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpSource
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
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
class BrowserResolverOwnedHumanFollowUpApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var claims: HumanFollowUpClaimService

    @Test
    fun `returns owned work without provider identity or authority attribution`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val owned = ownedWork(actorId)
        Mockito
            .`when`(claims.listOwned(tenantId, actorId, 25, CURSOR_AT, CURSOR_CLAIM_ID))
            .thenReturn(
                ResolverOwnedHumanFollowUpPage(
                    listOf(owned),
                    ResolverOwnedHumanFollowUpCursor(owned.claim.claim.claimedAt, owned.claim.claim.id),
                ),
            )

        mockMvc
            .perform(
                get(OWNED_PATH, tenantId)
                    .param("limit", "25")
                    .param("afterClaimedAt", CURSOR_AT.toString())
                    .param("afterClaimId", CURSOR_CLAIM_ID.toString())
                    .with(verifiedSession()),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(
                jsonPath("$.items[0].workItem.workItemId").value(
                    owned.workItem.item.id.value
                        .toString(),
                ),
            ).andExpect(jsonPath("$.items[0].workItem.queueKey").value("access-restoration"))
            .andExpect(jsonPath("$.items[0].workItem.status").value("OPEN"))
            .andExpect(
                jsonPath("$.items[0].claim.claimId").value(
                    owned.claim.claim.id.value
                        .toString(),
                ),
            ).andExpect(
                jsonPath("$.items[0].claim.workItemId").value(
                    owned.workItem.item.id.value
                        .toString(),
                ),
            ).andExpect(jsonPath("$.items[0].claim.claimedAt").value(CLAIMED_AT.toString()))
            .andExpect(jsonPath("$.items[0].claim.recordedAt").value(CLAIM_RECORDED_AT.toString()))
            .andExpect(jsonPath("$.nextCursor.afterClaimedAt").value(CLAIMED_AT.toString()))
            .andExpect(
                jsonPath("$.nextCursor.afterClaimId").value(
                    owned.claim.claim.id.value
                        .toString(),
                ),
            ).andExpect(jsonPath("$.items[0].claim.resolverActorId").doesNotExist())
            .andExpect(jsonPath("$.items[0].claim.authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.idToken").doesNotExist())

        Mockito.verify(claims).listOwned(tenantId, actorId, 25, CURSOR_AT, CURSOR_CLAIM_ID)
    }

    @Test
    fun `requires an authenticated browser session before reading owned work`() {
        mockMvc
            .perform(get(OWNED_PATH, UUID.randomUUID()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
            .andExpect(jsonPath("$.signInPath").value("/bff/login"))

        Mockito.verifyNoInteractions(claims)
    }

    @Test
    fun `does not disclose owned work through a tenant-foreign actor binding`() {
        registerActor(UUID.randomUUID())

        mockMvc
            .perform(get(OWNED_PATH, UUID.randomUUID()).with(verifiedSession()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))

        Mockito.verifyNoInteractions(claims)
    }

    @Test
    fun `preserves an empty page when the application hides owned work`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito
            .`when`(claims.listOwned(tenantId, actorId, 50, null, null))
            .thenReturn(ResolverOwnedHumanFollowUpPage(emptyList(), null))

        mockMvc
            .perform(get(OWNED_PATH, tenantId).with(verifiedSession()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.nextCursor").isEmpty)
    }

    @Test
    fun `returns the stable problem when owned-work pagination is invalid`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        Mockito
            .`when`(claims.listOwned(tenantId, actorId, 101, null, null))
            .thenThrow(InvalidResolverOwnedHumanFollowUpPageException("limit must be between 1 and 100"))

        mockMvc
            .perform(get(OWNED_PATH, tenantId).param("limit", "101").with(verifiedSession()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-resolver-owned-human-follow-up-page"))
            .andExpect(jsonPath("$.instance").value("/bff/v1/tenants/$tenantId/human-follow-ups/owned"))
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

    private fun ownedWork(actorId: UUID): ResolverOwnedHumanFollowUpWork {
        val workItem =
            HumanFollowUpWorkItem.open(
                HumanFollowUpWorkItemId(UUID.randomUUID()),
                HumanFollowUpSource(
                    CaseId(UUID.randomUUID()),
                    ResolutionRunId(UUID.randomUUID()),
                    ResolutionRunEventId(UUID.randomUUID()),
                    ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED,
                ),
                HumanFollowUpQueueKey.ACCESS_RESTORATION,
                OPENED_AT,
            )
        val authority =
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                HumanActorId(actorId),
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
                CLAIMED_AT.minusSeconds(60),
                CLAIMED_AT.plusSeconds(60),
            )
        val claim =
            HumanFollowUpClaim.claim(
                HumanFollowUpClaimId(UUID.randomUUID()),
                workItem.id,
                authority,
                CLAIMED_AT,
            )
        return ResolverOwnedHumanFollowUpWork(
            StoredHumanFollowUpWorkItem(workItem, WORK_RECORDED_AT),
            StoredHumanFollowUpClaim(claim, CLAIM_RECORDED_AT),
        )
    }

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val OWNED_PATH = "/bff/v1/tenants/{tenantId}/human-follow-ups/owned"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val ACTOR_REQUEST =
            """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
        private val OPENED_AT = Instant.parse("2026-09-21T09:30:00Z")
        private val WORK_RECORDED_AT = Instant.parse("2026-09-21T09:30:01Z")
        private val CLAIMED_AT = Instant.parse("2026-09-22T10:15:00Z")
        private val CLAIM_RECORDED_AT = Instant.parse("2026-09-22T10:15:01Z")
        private val CURSOR_AT = Instant.parse("2026-09-22T09:00:00Z")
        private val CURSOR_CLAIM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")

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
