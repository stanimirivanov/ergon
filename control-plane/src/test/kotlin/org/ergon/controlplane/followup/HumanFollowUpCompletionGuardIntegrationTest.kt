package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.followup.application.HumanFollowUpClaimNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.follow-up.release-enabled=true",
        "ergon.follow-up.handover-enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, CompletionGuardClockConfiguration::class)
class HumanFollowUpCompletionGuardIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val claims: HumanFollowUpClaimService,
    @Autowired private val claimRepository: HumanFollowUpClaimRepository,
    @Autowired private val mvc: MockMvc,
    @Autowired private val workItems: HumanFollowUpWorkItemRepository,
    @Autowired manager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(manager)

    @Test
    fun `terminal fact withdraws owned context and blocks every existing browser writer`() {
        val f = fixture()
        val path = "/bff/v1/tenants/${f.graph.tenant.value}/human-follow-ups"
        val source = sourceSnapshot(f)
        mvc
            .perform(
                post("$path/${f.work}/notes")
                    .with(RunEscalationTestFixture.session(f.graph.outgoing.value))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        noteBody(f, "retained commentary"),
                    ),
            ).andExpect(status().isCreated)
        complete(f)
        mvc
            .perform(get("$path/owned").with(RunEscalationTestFixture.session(f.graph.outgoing.value)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        for (suffix in listOf("case-summary", "notes")) {
            mvc
                .perform(get("$path/${f.work}/$suffix").with(RunEscalationTestFixture.session(f.graph.outgoing.value)))
                .andExpect(status().isNotFound)
        }
        assertBrowserWritersRejected(f, path)
        assertThat(claimRepository.findByWorkItem(f.graph.tenant, HumanFollowUpWorkItemId(f.work))).isNull()
        assertThat(
            workItems.findForResolver(
                f.graph.tenant,
                HumanFollowUpWorkItemId(f.work),
                HumanActorId(f.graph.outgoing.value),
                NOW,
            ),
        ).isNull()
        assertThatThrownBy { claims.get(f.graph.tenant.value, f.work, f.claim.id.value, f.graph.outgoing.value) }
            .isInstanceOf(HumanFollowUpClaimNotFoundException::class.java)
        assertThat(
            jdbc
                .sql("SELECT COUNT(*) FROM human_follow_up_notes WHERE tenant_id = :tenant AND work_item_id = :work")
                .param("tenant", f.graph.tenant.value)
                .param("work", f.work)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1)
        assertThat(sourceSnapshot(f)).isEqualTo(source)
        // Terminal truth must not become unclaimed work if a derived projection is absent.
        jdbc
            .sql("DELETE FROM human_follow_up_current_ownership WHERE tenant_id = :tenant AND work_item_id = :work")
            .param("tenant", f.graph.tenant.value)
            .param("work", f.work)
            .update()
        mvc
            .perform(get(path).with(RunEscalationTestFixture.session(f.graph.outgoing.value)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
    }

    @Test
    fun `fact is unique immutable and bound to exact claimed actor tenant and authority`() {
        val f = fixture()
        assertThatThrownBy { complete(f, tenant = UUID.randomUUID()) }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { complete(f, claim = UUID.randomUUID()) }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { complete(f, evidence = UUID.randomUUID()) }.isInstanceOf(DataAccessException::class.java)
        complete(f)
        assertThatThrownBy { complete(f) }.isInstanceOf(DataAccessException::class.java)
        for (sql in listOf(
            "UPDATE human_follow_up_completions SET completed_at = completed_at",
            "DELETE FROM human_follow_up_completions",
        )) {
            assertThatThrownBy {
                jdbc
                    .sql("$sql WHERE tenant_id = :tenant AND work_item_id = :work")
                    .param("tenant", f.graph.tenant.value)
                    .param("work", f.work)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `historical claimed event cannot complete work after a direct handover`() {
        val f = fixture()
        mvc
            .perform(
                post("/bff/v1/tenants/${f.graph.tenant.value}/human-follow-ups/${f.work}/handovers")
                    .with(RunEscalationTestFixture.session(f.graph.outgoing.value))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        handoverBody(f),
                    ),
            ).andExpect(status().isCreated)
        assertThatThrownBy { complete(f) }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `rolled back completion leaves active ownership and immutable opening intact`() {
        val f = fixture()
        transactions.executeWithoutResult { tx ->
            complete(f)
            assertThat(claimRepository.findByWorkItem(f.graph.tenant, HumanFollowUpWorkItemId(f.work))).isNull()
            tx.setRollbackOnly()
        }
        assertThat(claimRepository.findByWorkItem(f.graph.tenant, HumanFollowUpWorkItemId(f.work))?.claim)
            .isEqualTo(f.claim)
    }

    @Test
    fun `writer waiting on immutable source lock observes newly committed terminal fact`() {
        val f = fixture()
        val pid = AtomicInteger()
        Executors.newSingleThreadExecutor().use { executor ->
            val pending =
                requireNotNull(
                    transactions.execute {
                        complete(f)
                        val future =
                            executor.submit<Boolean> {
                                requireNotNull(
                                    transactions.execute {
                                        pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                                        claimRepository.lockOpenWorkItem(
                                            f.graph.tenant,
                                            HumanFollowUpWorkItemId(f.work),
                                        )
                                    },
                                )
                            }
                        // Observe PostgreSQL's actual blocker, not a pre-request latch or sleep.
                        await().atMost(20, TimeUnit.SECONDS).until {
                            pid.get() != 0 &&
                                jdbc
                                    .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                                    .param("pid", pid.get())
                                    .query(Boolean::class.java)
                                    .single()
                        }
                        future
                    },
                )
            assertThat(pending.get(20, TimeUnit.SECONDS)).isFalse()
        }
    }

    private fun assertBrowserWritersRejected(
        f: Owned,
        path: String,
    ) {
        val bodies =
            mapOf(
                "claims" to "{}",
                "claim-commands" to """{"commandId":"${UUID.randomUUID()}","expectedOwnershipRevision":1}""",
                "claims/${f.claim.id.value}/release" to """{"expectedOwnershipRevision":1}""",
                "handovers" to handoverBody(f),
                "notes" to noteBody(f, "must not append"),
            )
        for ((suffix, body) in bodies) {
            mvc
                .perform(
                    post("$path/${f.work}/$suffix")
                        .with(RunEscalationTestFixture.session(f.graph.outgoing.value))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isNotFound)
        }
    }

    private fun handoverBody(f: Owned): String =
        """
        {"commandId":"${UUID.randomUUID()}","claimId":"${f.claim.id.value}",
         "expectedOwnershipRevision":1,"recipientActorId":"${f.graph.incoming.value}"}
        """.trimIndent()

    private fun noteBody(
        f: Owned,
        text: String,
    ): String =
        """
        {"noteId":"${UUID.randomUUID()}","claimId":"${f.claim.id.value}",
         "expectedOwnershipRevision":1,"text":"$text"}
        """.trimIndent()

    private fun fixture(): Owned {
        val graph = requireNotNull(transactions.execute { RunSupervisionDatabaseFixture.seed(jdbc, NOW) })
        val work = UUID.randomUUID()
        transactions.executeWithoutResult {
            // Isolate only the prior escalation graph, never completion or ownership constraints.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
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
        return Owned(graph, work, claims.claim(graph.tenant.value, work, graph.outgoing.value).storedClaim.claim)
    }

    private fun complete(
        f: Owned,
        tenant: UUID = f.graph.tenant.value,
        claim: UUID = f.claim.id.value,
        evidence: UUID = f.claim.authorityEvidenceId.value,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO human_follow_up_completions
                    (tenant_id, work_item_id, command_id, claim_id, ownership_revision, actor_id, authority_evidence_id, completed_at)
                VALUES (:tenant, :work, :command, :claim, 1, :actor, :evidence, :at)
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("work", f.work)
            .param("command", UUID.randomUUID())
            .param("claim", claim)
            .param("actor", f.graph.outgoing.value)
            .param("evidence", evidence)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun sourceSnapshot(f: Owned): String =
        jdbc
            .sql(
                """
                SELECT jsonb_build_object('work', to_jsonb(item), 'run', to_jsonb(run),
                    'case', to_jsonb(case_row), 'state', to_jsonb(state), 'proof_count',
                    (SELECT COUNT(*) FROM resolution_outcome_proof_acceptances proof
                        WHERE proof.tenant_id = run.tenant_id AND proof.run_id = run.run_id),
                    'ownership', to_jsonb(ownership), 'event_count',
                    (SELECT COUNT(*) FROM human_follow_up_ownership_events event
                        WHERE event.tenant_id = item.tenant_id AND event.work_item_id = item.work_item_id))::TEXT
                FROM human_follow_up_work_items item
                JOIN resolution_runs run ON run.tenant_id = item.tenant_id AND run.run_id = item.run_id
                JOIN cases case_row ON case_row.tenant_id = run.tenant_id AND case_row.case_id = run.case_id
                JOIN resolution_run_states state ON state.tenant_id = run.tenant_id AND state.run_id = run.run_id
                JOIN human_follow_up_current_ownership ownership
                    ON ownership.tenant_id = item.tenant_id AND ownership.work_item_id = item.work_item_id
                WHERE item.tenant_id = :tenant AND item.work_item_id = :work
                """.trimIndent(),
            ).param("tenant", f.graph.tenant.value)
            .param("work", f.work)
            .query(String::class.java)
            .single()

    private data class Owned(
        val graph: RunSupervisionDatabaseFixture,
        val work: UUID,
        val claim: HumanFollowUpClaim,
    )

    companion object {
        val NOW: Instant = Instant.parse("2026-10-23T10:00:00Z")

        // Disposable bootstrap only; avoid initdb's full disk flush under shared I/O.
        // Does not change PostgreSQLContainer's runtime settings or test deadlines.
        @Container @JvmStatic
        val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withEnv("POSTGRES_INITDB_ARGS", "--no-sync")

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
private class CompletionGuardClockConfiguration {
    @Bean @Primary
    fun completionGuardClock(): Clock = Clock.fixed(HumanFollowUpCompletionGuardIntegrationTest.NOW, ZoneOffset.UTC)
}
