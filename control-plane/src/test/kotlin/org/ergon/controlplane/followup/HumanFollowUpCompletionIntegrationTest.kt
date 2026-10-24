package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionCommand
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionRepository
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionService
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionUnavailableException
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.TenantId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.follow-up.completion-enabled=true",
        "ergon.follow-up.release-enabled=true", "ergon.follow-up.handover-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, CompletionClockConfiguration::class)
@Suppress("LongParameterList") // Exercises real HTTP, ownership, authority and transaction boundaries together.
class HumanFollowUpCompletionIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val completions: HumanFollowUpCompletionService,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val claimRepository: HumanFollowUpClaimRepository,
    @Autowired private val repository: HumanFollowUpCompletionRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val runner: TransactionRunner,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

    @Test
    fun `browser requires session actor and CSRF and returns identical bounded terminal acknowledgement`() {
        val c = fixture().command
        mvc
            .perform(post(path(c)).contentType(MediaType.APPLICATION_JSON).content(body(c)))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path(c))
                    .with(RunEscalationTestFixture.session(c.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(c)),
            ).andExpect(status().isForbidden)
        postCompletion(c.copy(actorId = UUID.randomUUID())).andExpect(status().isForbidden)
        val first =
            postCompletion(c)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(jsonPath("$.commandId").value(c.commandId.toString()))
                .andExpect(jsonPath("$.workItemId").value(c.workItemId.toString()))
                .andExpect(jsonPath("$.claimId").value(c.claimId.toString()))
                .andExpect(jsonPath("$.expectedOwnershipRevision").value(1))
                .andExpect(jsonPath("$.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.recordedAt").isString)
                .andReturn()
                .response.contentAsString
        assertThat(
            postCompletion(c)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        assertThat(first).doesNotContain("actorId", "authority", "caseId", "runId", "provider", "token")
        mvc
            .perform(
                get("/bff/v1/tenants/${c.tenantId}/human-follow-ups/owned")
                    .with(RunEscalationTestFixture.session(c.actorId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        for (suffix in listOf("case-summary", "notes")) {
            mvc
                .perform(
                    get("/bff/v1/tenants/${c.tenantId}/human-follow-ups/${c.workItemId}/$suffix")
                        .with(RunEscalationTestFixture.session(c.actorId)),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `stale coordinates conflict and invalid hidden or foreign commands cannot complete`() {
        val f = fixture()
        val c = f.command
        assertThatThrownBy { service(Clock.fixed(NOW, ZoneOffset.UTC), enabled = false).complete(c) }
            .isInstanceOf(HumanFollowUpCompletionUnavailableException::class.java)
        for (changed in listOf(c.copy(claimId = UUID.randomUUID()), c.copy(expectedOwnershipRevision = 3))) {
            postCompletion(changed)
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-completion-conflict"))
        }
        for (revision in listOf(0L, -1L)) {
            postCompletion(c.copy(expectedOwnershipRevision = revision)).andExpect(status().isBadRequest)
        }
        for (hidden in listOf(
            c.copy(actorId = f.graph.incoming.value),
            c.copy(actorId = f.graph.expired.value),
            c.copy(workItemId = UUID.randomUUID()),
            c.copy(tenantId = UUID.randomUUID()),
        )) {
            assertThatThrownBy { completions.complete(hidden) }
                .isInstanceOf(HumanFollowUpCompletionNotFoundException::class.java)
        }
        assertThat(repository.find(c)).isNull()
    }

    @Test
    fun `terminal replay rejects changed intent other actors expired authority and new command identities`() {
        val f = fixture()
        val c = f.command
        val first = completions.complete(c).receipt
        for (changed in listOf(c.copy(claimId = UUID.randomUUID()), c.copy(expectedOwnershipRevision = 3))) {
            assertThatThrownBy { completions.complete(changed) }
                .isInstanceOf(HumanFollowUpCompletionConflictException::class.java)
        }
        for (hidden in listOf(c.copy(actorId = f.graph.incoming.value), c.copy(commandId = UUID.randomUUID()))) {
            assertThatThrownBy { completions.complete(hidden) }
                .isInstanceOf(HumanFollowUpCompletionNotFoundException::class.java)
        }
        assertThatThrownBy { service(Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC)).complete(c) }
            .isInstanceOf(HumanFollowUpCompletionNotFoundException::class.java)
        assertThat(completions.complete(c).receipt).isEqualTo(first)
        assertThatThrownBy { service(Clock.fixed(NOW, ZoneOffset.UTC), enabled = false).complete(c) }
            .isInstanceOf(HumanFollowUpCompletionUnavailableException::class.java)
    }

    @Test
    fun `rollback leaves work active and identical concurrent commands acknowledge one durable winner`() {
        val c = fixture().command
        transactions.executeWithoutResult { tx ->
            completions.complete(c)
            tx.setRollbackOnly()
        }
        assertThat(repository.find(c)).isNull()
        assertThat(claimRepository.findByWorkItem(TenantId(c.tenantId), HumanFollowUpWorkItemId(c.workItemId)))
            .isNotNull()
        val results = RunEscalationTestFixture.together({ completions.complete(c) }, { completions.complete(c) })
        assertThat(results.count { it.created }).isEqualTo(1)
        assertThat(results[0].receipt).isEqualTo(results[1].receipt)
    }

    @Test
    fun `different completion intents serialize and the loser cannot disclose the winner`() {
        val c = fixture().command
        val results =
            RunEscalationTestFixture.together(
                { runCatching { completions.complete(c) }.exceptionOrNull() },
                { runCatching { completions.complete(c.copy(commandId = UUID.randomUUID())) }.exceptionOrNull() },
            )
        assertThat(results.count { it == null }).isEqualTo(1)
        assertThat(results.filterNotNull().single()).isInstanceOf(HumanFollowUpCompletionNotFoundException::class.java)
    }

    @Test
    fun `completion serializes with release and direct handover without partial ownership`() {
        for (operation in listOf("release", "handovers")) {
            val f = fixture()
            val c = f.command
            val suffix = if (operation == "release") "claims/${c.claimId}/release" else operation
            val payload =
                if (operation == "release") {
                    """{"expectedOwnershipRevision":1}"""
                } else {
                    """{"commandId":"${UUID.randomUUID()}","claimId":"${c.claimId}",
                    "expectedOwnershipRevision":1,"recipientActorId":"${f.graph.incoming.value}"}"""
                }
            val results =
                RunEscalationTestFixture.together(
                    { postCompletion(c).andReturn().response.status },
                    {
                        mvc
                            .perform(
                                post("/bff/v1/tenants/${c.tenantId}/human-follow-ups/${c.workItemId}/$suffix")
                                    .with(RunEscalationTestFixture.session(c.actorId))
                                    .with(csrf().asHeader())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload),
                            ).andReturn()
                            .response.status
                    },
                )
            assertThat(results).containsExactlyInAnyOrder(201, 404)
        }
    }

    @Test
    fun `authority is sampled after the database lock wait not before it`() {
        val c = fixture().command
        val reads = AtomicInteger()
        val pid = AtomicInteger()
        val expiredClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = Clock.fixed(NOW.plusSeconds(3600), zone)

                override fun instant(): Instant {
                    reads.incrementAndGet()
                    return NOW.plusSeconds(3600)
                }
            }
        Executors.newSingleThreadExecutor().use { executor ->
            val pending =
                requireNotNull(
                    transactions.execute {
                        claimRepository.lockOpenWorkItem(TenantId(c.tenantId), HumanFollowUpWorkItemId(c.workItemId))
                        val future =
                            executor.submit<Throwable?> {
                                runCatching {
                                    runner.required {
                                        pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                                        service(expiredClock).complete(c)
                                    }
                                }.exceptionOrNull()
                            }
                        await().atMost(20, TimeUnit.SECONDS).until {
                            pid.get() != 0 &&
                                jdbc
                                    .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                                    .param("pid", pid.get())
                                    .query(Boolean::class.java)
                                    .single()
                        }
                        assertThat(reads.get()).isZero()
                        future
                    },
                )
            assertThat(pending.get(20, TimeUnit.SECONDS))
                .isInstanceOf(HumanFollowUpCompletionNotFoundException::class.java)
        }
        assertThat(reads.get()).isEqualTo(1)
        assertThat(repository.find(c)).isNull()
    }

    private fun service(
        clock: Clock,
        enabled: Boolean = true,
    ) = HumanFollowUpCompletionService(claimRepository, repository, authorities, runner, clock, enabled)

    private fun fixture(): Owned {
        val graph = requireNotNull(transactions.execute { RunSupervisionDatabaseFixture.seed(jdbc, NOW) })
        val work = UUID.randomUUID()
        transactions.executeWithoutResult {
            // Isolate only the prior escalation source; completion and ownership constraints stay enabled.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items
                        (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                    VALUES (:tenant, :work, :run, :event, 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :at)
                    """.trimIndent(),
                ).param("tenant", graph.tenant.value)
                .param("work", work)
                .param("run", graph.run.value)
                .param("event", UUID.randomUUID())
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update()
            jdbc.sql("SET LOCAL session_replication_role = origin").update()
        }
        val claim = claims.claim(graph.tenant.value, work, graph.outgoing.value).storedClaim.claim
        return Owned(
            HumanFollowUpCompletionCommand(
                graph.tenant.value,
                work,
                graph.outgoing.value,
                UUID.randomUUID(),
                claim.id.value,
                1,
            ),
            graph,
        )
    }

    private fun postCompletion(c: HumanFollowUpCompletionCommand) =
        mvc.perform(
            post(path(c))
                .with(RunEscalationTestFixture.session(c.actorId))
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(c)),
        )

    private fun path(c: HumanFollowUpCompletionCommand) =
        "/bff/v1/tenants/${c.tenantId}/human-follow-ups/" +
            "${c.workItemId}/completions"

    private fun body(c: HumanFollowUpCompletionCommand) =
        """{"commandId":"${c.commandId}","claimId":"${c.claimId}",
            "expectedOwnershipRevision":${c.expectedOwnershipRevision}}"""

    private data class Owned(
        val command: HumanFollowUpCompletionCommand,
        val graph: RunSupervisionDatabaseFixture,
    )

    companion object {
        val NOW: Instant = Instant.parse("2026-10-24T10:00:00Z")

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
private class CompletionClockConfiguration {
    @Bean @Primary
    fun completionClock(): Clock = Clock.fixed(HumanFollowUpCompletionIntegrationTest.NOW, ZoneOffset.UTC)
}
