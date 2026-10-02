package org.ergon.controlplane.followup

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
        "ergon.follow-up.release-enabled=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedRevisionedClaimClockConfiguration::class)
class RevisionedHumanFollowUpClaimApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcClient: JdbcClient,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `claim command requires bearer authentication`() {
        mockMvc
            .perform(
                post(COMMAND_PATH, UUID.randomUUID(), UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(commandBody(UUID.randomUUID(), 0)),
            ).andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
    }

    @Test
    fun `release requires bearer authentication`() {
        mockMvc
            .perform(
                post(RELEASE_PATH, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedOwnershipRevision":1}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
    }

    @Test
    fun `first claim and exact retry retain one durable result`() {
        val tenantId = UUID.randomUUID()
        val workItemId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        seedOpenWork(tenantId, workItemId, actorId)
        val commandId = UUID.randomUUID()

        val created =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, commandId, 0))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.commandId").value(commandId.toString()))
                .andExpect(jsonPath("$.ownershipRevision").value(1))
                .andExpect(jsonPath("$.claim.workItemId").value(workItemId.toString()))
                .andExpect(jsonPath("$.claim.resolverActorId").value(actorId.toString()))
                .andReturn()
        val replayed =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, commandId, 0))
                .andExpect(status().isOk)
                .andReturn()
        assertThat(replayed.response.contentAsString).isEqualTo(created.response.contentAsString)
        assertThat(replayed.response.getHeader("Location")).isEqualTo(created.response.getHeader("Location"))

        mockMvc
            .perform(commandRequest(tenantId, workItemId, commandId, 1))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-claim-command-conflict"))
        mockMvc
            .perform(commandRequest(tenantId, workItemId, UUID.randomUUID(), 0))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-ownership-revision-conflict"))
        mockMvc
            .perform(commandRequest(tenantId, workItemId, UUID.randomUUID(), -1))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-claim-command"))

        assertThat(count("human_follow_up_claims", tenantId, workItemId)).isEqualTo(1)
        assertThat(count("human_follow_up_ownership_events", tenantId, workItemId)).isEqualTo(1)
        assertThat(count("human_follow_up_claim_commands", tenantId, workItemId)).isEqualTo(1)
        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE human_follow_up_claim_commands
                    SET expected_ownership_revision = 1
                    WHERE tenant_id = :tenantId AND work_item_id = :workItemId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("workItemId", workItemId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `release returns work to the inbox and a later command creates a distinct owner`() {
        val tenantId = UUID.randomUUID()
        val workItemId = UUID.randomUUID()
        val firstActorId = registerActor(tenantId)
        val nextActorId = registerActor(tenantId, NEXT_SUBJECT)
        seedOpenWork(tenantId, workItemId, firstActorId)
        seedResolverAuthority(tenantId, nextActorId)

        val first =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, UUID.randomUUID(), 0))
                .andExpect(status().isCreated)
                .andReturn()
        val firstClaimId = UUID.fromString(JsonPath.read(first.response.contentAsString, "$.claim.claimId"))

        mockMvc
            .perform(releaseRequest(tenantId, workItemId, firstClaimId, 1, NEXT_SUBJECT))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-release-not-found"))
        val released =
            mockMvc
                .perform(releaseRequest(tenantId, workItemId, firstClaimId, 1))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.ownershipRevision").value(2))
                .andExpect(jsonPath("$.claimId").value(firstClaimId.toString()))
                .andReturn()
        assertReleasedVisibility(tenantId, workItemId)

        val laterCommandId = UUID.randomUUID()
        val later =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, laterCommandId, 2, NEXT_SUBJECT))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.ownershipRevision").value(3))
                .andExpect(jsonPath("$.claim.resolverActorId").value(nextActorId.toString()))
                .andReturn()
        val laterClaimId = UUID.fromString(JsonPath.read(later.response.contentAsString, "$.claim.claimId"))
        assertThat(laterClaimId).isNotEqualTo(firstClaimId)
        assertLaterOwnership(tenantId, workItemId, laterCommandId, firstClaimId, laterClaimId)

        val replayedRelease =
            mockMvc
                .perform(releaseRequest(tenantId, workItemId, firstClaimId, 1))
                .andExpect(status().isOk)
                .andReturn()
        assertThat(replayedRelease.response.contentAsString).isEqualTo(released.response.contentAsString)
        mockMvc
            .perform(releaseRequest(tenantId, workItemId, laterClaimId, 2, NEXT_SUBJECT))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-ownership-revision-conflict"))
        assertThat(count("human_follow_up_claims", tenantId, workItemId)).isEqualTo(1)
        assertThat(count("human_follow_up_ownership_events", tenantId, workItemId)).isEqualTo(3)
        assertThat(count("human_follow_up_claim_commands", tenantId, workItemId)).isEqualTo(2)
    }

    @Test
    fun `competing later claims serialize to one new owner`() {
        val tenantId = UUID.randomUUID()
        val workItemId = UUID.randomUUID()
        val firstActorId = registerActor(tenantId)
        val nextActorId = registerActor(tenantId, NEXT_SUBJECT)
        seedOpenWork(tenantId, workItemId, firstActorId)
        seedResolverAuthority(tenantId, nextActorId)
        val first =
            mockMvc
                .perform(commandRequest(tenantId, workItemId, UUID.randomUUID(), 0))
                .andExpect(status().isCreated)
                .andReturn()
        val firstClaimId = UUID.fromString(JsonPath.read(first.response.contentAsString, "$.claim.claimId"))
        mockMvc
            .perform(releaseRequest(tenantId, workItemId, firstClaimId, 1))
            .andExpect(status().isCreated)

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val contenders =
                listOf(SUBJECT, NEXT_SUBJECT).map { subject ->
                    executor.submit<Int> {
                        ready.countDown()
                        check(start.await(30, TimeUnit.SECONDS))
                        mockMvc
                            .perform(commandRequest(tenantId, workItemId, UUID.randomUUID(), 2, subject))
                            .andReturn()
                            .response.status
                    }
                }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(contenders.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(201, 409)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
        assertThat(count("human_follow_up_ownership_events", tenantId, workItemId)).isEqualTo(3)
        assertThat(count("human_follow_up_claim_commands", tenantId, workItemId)).isEqualTo(2)
    }

    private fun assertReleasedVisibility(
        tenantId: UUID,
        workItemId: UUID,
    ) {
        mockMvc
            .perform(get(INBOX_PATH, tenantId).with(resolverJwt(NEXT_SUBJECT)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].workItemId").value(workItemId.toString()))
        mockMvc
            .perform(get(OWNED_PATH, tenantId).with(resolverJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        mockMvc
            .perform(post(LEGACY_CLAIM_PATH, tenantId, workItemId).with(resolverJwt()))
            .andExpect(status().isConflict)
    }

    private fun assertLaterOwnership(
        tenantId: UUID,
        workItemId: UUID,
        commandId: UUID,
        firstClaimId: UUID,
        laterClaimId: UUID,
    ) {
        mockMvc
            .perform(commandRequest(tenantId, workItemId, commandId, 2, NEXT_SUBJECT))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.claim.claimId").value(laterClaimId.toString()))
        mockMvc
            .perform(get(OWNED_PATH, tenantId).with(resolverJwt(NEXT_SUBJECT)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].claim.claimId").value(laterClaimId.toString()))
        mockMvc
            .perform(post(LEGACY_CLAIM_PATH, tenantId, workItemId).with(resolverJwt(NEXT_SUBJECT)))
            .andExpect(status().isConflict)
        mockMvc
            .perform(get(CLAIM_PATH, tenantId, workItemId, firstClaimId).with(resolverJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.claimId").value(firstClaimId.toString()))
    }

    private fun commandRequest(
        tenantId: UUID,
        workItemId: UUID,
        commandId: UUID,
        expectedRevision: Long,
        subject: String = SUBJECT,
    ) = post(COMMAND_PATH, tenantId, workItemId)
        .with(resolverJwt(subject))
        .contentType(MediaType.APPLICATION_JSON)
        .content(commandBody(commandId, expectedRevision))

    private fun releaseRequest(
        tenantId: UUID,
        workItemId: UUID,
        claimId: UUID,
        expectedRevision: Long,
        subject: String = SUBJECT,
    ) = post(RELEASE_PATH, tenantId, workItemId, claimId)
        .with(resolverJwt(subject))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"expectedOwnershipRevision":$expectedRevision}""")

    private fun resolverJwt(subject: String = SUBJECT) =
        jwt().jwt {
            it.issuer(TRUSTED_ISSUER)
            it.subject(subject)
        }

    private fun commandBody(
        commandId: UUID,
        expectedRevision: Long,
    ) = """{"commandId":"$commandId","expectedOwnershipRevision":$expectedRevision}"""

    private fun registerActor(
        tenantId: UUID,
        subject: String = SUBJECT,
    ): UUID {
        val result =
            mockMvc
                .perform(
                    post(ACTORS_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"identityProvider":"workforce-sso","subject":"$subject"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun seedOpenWork(
        tenantId: UUID,
        workItemId: UUID,
        actorId: UUID,
    ) {
        val runId = UUID.randomUUID()
        transactions.executeWithoutResult {
            // The source graph belongs to earlier slices; restore triggers before
            // exercising the real claim command and ownership projection.
            jdbcClient.sql("SET LOCAL session_replication_role = replica").update()
            seedRun(tenantId, runId)
            seedWorkItem(tenantId, workItemId, runId)
            seedResolverAuthority(tenantId, actorId)
            jdbcClient.sql("SET LOCAL session_replication_role = origin").update()
        }
    }

    private fun seedRun(
        tenantId: UUID,
        runId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_runs (
                    tenant_id, run_id, case_id, case_stream_version,
                    contract_key, contract_revision, policy_revision,
                    step_id, capability, effective_risk, required_approval,
                    initial_state
                ) VALUES (
                    :tenantId, :runId, :caseId, 1,
                    'restore-workspace-access', 1, 'ergon.dev/policy/access-restoration/v1',
                    'unlock-account', 'identity.account.unlock', 'HIGH', 'RESOLVER',
                    'WAITING_FOR_APPROVAL'
                )
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("runId", runId)
            .param("caseId", UUID.randomUUID())
            .update()
    }

    private fun seedWorkItem(
        tenantId: UUID,
        workItemId: UUID,
        runId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO human_follow_up_work_items (
                    tenant_id, work_item_id, run_id, escalation_event_id,
                    reason, status, opened_at
                ) VALUES (
                    :tenantId, :workItemId, :runId, :escalationEventId,
                    'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :openedAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("workItemId", workItemId)
            .param("runId", runId)
            .param("escalationEventId", UUID.randomUUID())
            .param("openedAt", NOW.minusSeconds(30).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun seedResolverAuthority(
        tenantId: UUID,
        actorId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO approval_authority_evidence (
                    tenant_id, evidence_id, actor_id, authority, case_id,
                    source_provider, source_reference, attested_at, expires_at
                ) VALUES (
                    :tenantId, :evidenceId, :actorId, 'RESOLVER', NULL,
                    'workforce-sso', 'groups/resolvers', :attestedAt, :expiresAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("evidenceId", UUID.randomUUID())
            .param("actorId", actorId)
            .param("attestedAt", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))
            .param("expiresAt", NOW.plusSeconds(60).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun count(
        table: String,
        tenantId: UUID,
        workItemId: UUID,
    ): Long =
        jdbcClient
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenantId AND work_item_id = :workItemId")
            .param("tenantId", tenantId)
            .param("workItemId", workItemId)
            .query(Long::class.java)
            .single()

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val COMMAND_PATH = "/internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claim-commands"
        private const val RELEASE_PATH =
            "/internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/" +
                "{claimId}/release"
        private const val LEGACY_CLAIM_PATH = "/internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims"
        private const val CLAIM_PATH = "/internal/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/{claimId}"
        private const val INBOX_PATH = "/internal/v1/tenants/{tenantId}/human-follow-ups"
        private const val OWNED_PATH = "/internal/v1/tenants/{tenantId}/human-follow-ups/owned"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val SUBJECT = "employee-42"
        private const val NEXT_SUBJECT = "employee-43"
        private val NOW = Instant.parse("2026-09-14T10:00:00Z")

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

@TestConfiguration(proxyBeanMethods = false)
private class FixedRevisionedClaimClockConfiguration {
    @Bean
    @Primary
    fun fixedRevisionedClaimClock(): Clock = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC)
}
