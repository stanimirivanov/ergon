package org.ergon.controlplane.followup

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpInboxQuery
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemCursor
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemPage
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpQueueException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpWorkItemPageException
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpSource
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
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
class BrowserHumanFollowUpInboxApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var workItems: HumanFollowUpWorkItemQueryService

    @Test
    fun `returns a bounded browser inbox without provider identity or tokens`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val workItem = workItem()
        val expectedQuery =
            HumanFollowUpInboxQuery(
                tenantId,
                actorId,
                "access-restoration",
                25,
                CURSOR_AT,
                CURSOR_WORK_ITEM_ID,
            )
        Mockito
            .`when`(workItems.listOpen(expectedQuery))
            .thenReturn(
                HumanFollowUpWorkItemPage(
                    listOf(StoredHumanFollowUpWorkItem(workItem, RECORDED_AT)),
                    HumanFollowUpWorkItemCursor(workItem.openedAt, workItem.id),
                ),
            )

        mockMvc
            .perform(
                get(INBOX_PATH, tenantId)
                    .param("queueKey", "access-restoration")
                    .param("limit", "25")
                    .param("afterOpenedAt", CURSOR_AT.toString())
                    .param("afterWorkItemId", CURSOR_WORK_ITEM_ID.toString())
                    .with(verifiedSession()),
            ).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
            .andExpect(jsonPath("$.items[0].workItemId").value(workItem.id.value.toString()))
            .andExpect(jsonPath("$.items[0].caseId").value(workItem.caseId.value.toString()))
            .andExpect(jsonPath("$.items[0].queueKey").value("access-restoration"))
            .andExpect(jsonPath("$.items[0].status").value("OPEN"))
            .andExpect(jsonPath("$.items[0].recordedAt").value(RECORDED_AT.toString()))
            .andExpect(jsonPath("$.nextCursor.afterOpenedAt").value(workItem.openedAt.toString()))
            .andExpect(jsonPath("$.nextCursor.afterWorkItemId").value(workItem.id.value.toString()))
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.idToken").doesNotExist())

        Mockito.verify(workItems).listOpen(expectedQuery)
    }

    @Test
    fun `requires an authenticated browser session before reading the inbox`() {
        mockMvc
            .perform(get(INBOX_PATH, UUID.randomUUID()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
            .andExpect(jsonPath("$.signInPath").value("/bff/login"))
    }

    @Test
    fun `does not disclose a tenant-foreign actor binding`() {
        registerActor(UUID.randomUUID())

        mockMvc
            .perform(get(INBOX_PATH, UUID.randomUUID()).with(verifiedSession()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))

        Mockito.verifyNoInteractions(workItems)
    }

    @Test
    fun `returns the stable page problem when application validation rejects query input`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val expectedQuery = HumanFollowUpInboxQuery(tenantId, actorId, null, 101, null, null)
        Mockito
            .`when`(workItems.listOpen(expectedQuery))
            .thenThrow(InvalidHumanFollowUpWorkItemPageException("limit must be between 1 and 100"))

        mockMvc
            .perform(get(INBOX_PATH, tenantId).param("limit", "101").with(verifiedSession()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-page"))
            .andExpect(jsonPath("$.instance").value("/bff/v1/tenants/$tenantId/human-follow-ups"))
    }

    @Test
    fun `returns the stable queue problem when application validation rejects a filter`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val expectedQuery = HumanFollowUpInboxQuery(tenantId, actorId, "UpperCase", 50, null, null)
        Mockito
            .`when`(workItems.listOpen(expectedQuery))
            .thenThrow(InvalidHumanFollowUpQueueException(IllegalArgumentException("invalid queue")))

        mockMvc
            .perform(get(INBOX_PATH, tenantId).param("queueKey", "UpperCase").with(verifiedSession()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-queue"))
            .andExpect(jsonPath("$.instance").value("/bff/v1/tenants/$tenantId/human-follow-ups"))
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

    private fun workItem(): HumanFollowUpWorkItem =
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

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val INBOX_PATH = "/bff/v1/tenants/{tenantId}/human-follow-ups"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val ACTOR_REQUEST =
            """{"identityProvider":"workforce-sso","subject":"$SUBJECT"}"""
        private val OPENED_AT = Instant.parse("2026-09-21T09:30:00Z")
        private val RECORDED_AT = Instant.parse("2026-09-21T09:30:01Z")
        private val CURSOR_AT = Instant.parse("2026-09-21T09:00:00Z")
        private val CURSOR_WORK_ITEM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")

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
