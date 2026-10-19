package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.ResumeRunExecutionCommand
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionKind
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionService
import org.ergon.controlplane.resolution.application.RunExecutionCommandRepository
import org.ergon.controlplane.resolution.application.RunExecutionHistoryReader
import org.ergon.controlplane.resolution.application.RunExecutionHistoryService
import org.ergon.controlplane.resolution.application.RunExecutionService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.execution.enabled=true",
        "ergon.run-supervision.handover.enabled=true",
        "spring.flyway.target=20261018210000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(
    BrowserSessionTestClientConfiguration::class,
    RunExecutionHistoryIntegrationTest.HistoryTestConfiguration::class,
)
@Suppress("LongParameterList") // Real authority, admission and audit ports prove a non-mutating cross-epoch read.
class RunExecutionHistoryIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val executions: RunExecutionService,
    @Autowired private val admissions: RunExecutionAdmissionService,
    @Autowired requests: ApprovalRequestService,
    @Autowired decisions: ApprovalDecisionService,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val states: ResolutionRunTransitionRepository,
    @Autowired private val commands: RunExecutionCommandRepository,
    @Autowired private val history: RunExecutionHistoryService,
    @Autowired private val historyReader: RunExecutionHistoryReader,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val gateway: RunExecutionTestConnector,
    @Autowired private val flyway: Flyway,
) {
    private val fixtures = RunExecutionTestFixture(jdbc, transactions, requests, decisions, NOW)

    @BeforeEach
    fun upgradeIndexWithExistingAdmissions() =
        synchronized(postgres) {
            if (flyway
                    .info()
                    .current()
                    .version
                    .toString() == "20261018210000"
            ) {
                val (fixture, command) = fixtures.seed()
                admissions.admit(command)
                val before = historyReader.read(fixture.tenant, fixture.run)
                Flyway
                    .configure()
                    .configuration(flyway.configuration)
                    .target("latest")
                    .load()
                    .migrate()
                assertThat(historyReader.read(fixture.tenant, fixture.run)).isEqualTo(before)
                assertThat(
                    jdbc
                        .sql("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ix_run_execution_attempts_history'")
                        .query(Int::class.java)
                        .single(),
                ).isEqualTo(1)
            }
        }

    @Test
    fun `absent browser history is explicit and creates no execution command`() {
        val (fixture, command) = fixtures.seed()
        assertThat(history.get(command.tenantId, command.runId, command.actorId).admissions).isNull()
        mvc
            .perform(get(base(command) + "/execution-history").with(session(fixture.outgoing)))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.commandRecorded").value(false))
            .andExpect(jsonPath("$.entries").isEmpty())
            .andExpect(jsonPath("$.truncated").value(false))
        assertThat(count("resolution_run_execution_commands", command.tenantId)).isZero()
        assertThat(gateway.calls(command.tenantId)).isZero()
    }

    @Test
    fun `history is sanitized read-only and remains available to the incoming supervisor`() {
        val (fixture, command) = fixtures.seed()
        admissions.admit(command)
        admissions.resume(resume(command))
        val before = history.get(command.tenantId, command.runId, command.actorId)
        assertThat(before.admissions?.entries?.map { it.kind })
            .containsExactlyInAnyOrder(RunExecutionAdmissionKind.INITIAL, RunExecutionAdmissionKind.RECOVERY)
        assertThat(before.admissions?.truncated).isFalse()
        handover(fixture, fixture.outgoing, fixture.incoming, 0)
        val after = history.get(command.tenantId, command.runId, fixture.incoming.value)
        assertThat(after.supervisionRevision.value).isEqualTo(1)
        assertThat(after.admissions).isEqualTo(before.admissions)
        assertThatThrownBy { history.get(command.tenantId, command.runId, command.actorId) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)

        val response =
            mvc
                .perform(get(base(command) + "/execution-history").with(session(fixture.incoming)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.commandRecorded").value(true))
                .andExpect(jsonPath("$.supervisionRevision").value(1))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].supervisionRevision").value(0))
                .andReturn()
                .response.contentAsString
        for (secret in listOf(command.actorId, fixture.incoming.value, command.commandId, command.decisionId)) {
            assertThat(response).doesNotContain(secret.toString())
        }
        val privateFields =
            listOf(
                "actorId",
                "attemptId",
                "commandId",
                "control",
                "authorizationGrantId",
                "outcome",
                "elapsedNanoseconds",
            )
        for (field in privateFields) {
            assertThat(response).doesNotContain("\"$field\"")
        }
        assertThat(history.get(command.tenantId, command.runId, fixture.incoming.value)).isEqualTo(after)
        assertThat(gateway.calls(command.tenantId)).isZero()
        assertThat(count("resolution_run_execution_attempts", command.tenantId)).isEqualTo(2)
        assertThat(count("capability_authorization_consumptions", command.tenantId)).isEqualTo(1)
        assertThat(count("capability_invocation_receipts", command.tenantId)).isZero()
        assertThat(states.findState(fixture.tenant, fixture.run)?.version).isZero()
        // New supervisors can inspect, but cannot adopt the original actor's reservation.
        mvc
            .perform(get(base(command) + "/execution-review").with(session(fixture.incoming)))
            .andExpect(jsonPath("$.canResume").value(false))
            .andExpect(jsonPath("$.commandId").isEmpty())
    }

    @Test
    fun `recorded-result history requires current access without acquiring a live lease`() {
        val (fixture, command) = fixtures.seed()
        executions.execute(command)
        mvc
            .perform(get(base(command) + "/execution-history").with(session(fixture.outgoing)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries[0].kind").value("INITIAL"))
            .andExpect(jsonPath("$.entries[0].admittedAt").value(NOW.toString()))
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        mvc
            .perform(get(base(command) + "/execution-history"))
            .andExpect(status().isUnauthorized)
        for (actor in listOf(fixture.incoming, fixture.expired)) {
            mvc
                .perform(get(base(command) + "/execution-history").with(session(actor)))
                .andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:assigned-resolution-run-not-found"))
        }
        assertThat(count("resolution_run_control_events", command.tenantId)).isZero()
    }

    @Test
    fun `history gates reject before audit lookup and isolate tenant and absent run`() {
        val (fixture, command) = fixtures.seed()
        var reads = 0
        val guarded =
            RunExecutionHistoryService(
                assignments,
                authorities,
                RunExecutionHistoryReader { _, _ ->
                    reads++
                    error("private audit must not be loaded")
                },
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
            )
        val denied =
            listOf(
                Triple(command.tenantId, command.runId, fixture.incoming.value),
                Triple(command.tenantId, command.runId, fixture.expired.value),
                Triple(UUID.randomUUID(), command.runId, command.actorId),
                Triple(command.tenantId, UUID.randomUUID(), command.actorId),
            )
        for ((tenant, run, actor) in denied) {
            assertThatThrownBy { guarded.get(tenant, run, actor) }
                .isInstanceOf(AssignedRunNotFoundException::class.java)
        }
        val expired =
            RunExecutionHistoryService(
                assignments,
                authorities,
                RunExecutionHistoryReader { _, _ ->
                    reads++
                    error("expired authority cannot load audit")
                },
                transactions,
                Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC),
            )
        assertThatThrownBy { expired.get(command.tenantId, command.runId, command.actorId) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThat(reads).isZero()
        admissions.admit(command)
        assertThat(historyReader.read(TenantId(UUID.randomUUID()), fixture.run)).isNull()
        assertThat(historyReader.read(fixture.tenant, ResolutionRunId(UUID.randomUUID()))).isNull()
    }

    @Test
    fun `history bounds equal recording times by private UUID without inferring causal order`() {
        val (fixture, command) = fixtures.seed()
        admissions.admit(command)
        val recorded = checkNotNull(commands.findByRun(fixture.tenant, fixture.run)).recordedAt.minusSeconds(1)
        // Immutable copies are new recovery admissions, not updates/backfills to production audit.
        // Fixed recording times and backwards admission times make the tie-breaker observable.
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_execution_attempts (
                    tenant_id, attempt_id, command_id, run_id, actor_id, supervision_revision,
                    supervised_at, expected_run_version, authority_evidence_id, admitted_at, recorded_at
                )
                SELECT tenant_id, LPAD(TO_HEX(n), 32, '0')::UUID, command_id, run_id, actor_id,
                    supervision_revision, supervised_at, expected_run_version, authority_evidence_id,
                    :at - n * INTERVAL '1 second', :recorded
                FROM resolution_run_execution_attempts CROSS JOIN GENERATE_SERIES(1, 99) n
                WHERE tenant_id = :tenant AND attempt_id = :command
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("command", command.commandId)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .param("recorded", recorded.atOffset(ZoneOffset.UTC))
            .update()
        val exact = checkNotNull(history.get(command.tenantId, command.runId, command.actorId).admissions)
        assertThat(exact.entries).hasSize(100)
        assertThat(exact.truncated).isFalse()
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_execution_attempts (
                    tenant_id, attempt_id, command_id, run_id, actor_id, supervision_revision,
                    supervised_at, expected_run_version, authority_evidence_id, admitted_at, recorded_at
                )
                SELECT tenant_id, '00000000-0000-0000-0000-000000000064'::UUID, command_id, run_id, actor_id,
                    supervision_revision, supervised_at, expected_run_version, authority_evidence_id,
                    :at - INTERVAL '100 seconds', :recorded
                FROM resolution_run_execution_attempts WHERE tenant_id = :tenant AND attempt_id = :command
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("command", command.commandId)
            .param("at", NOW.atOffset(ZoneOffset.UTC))
            .param("recorded", recorded.atOffset(ZoneOffset.UTC))
            .update()
        val bounded = checkNotNull(history.get(command.tenantId, command.runId, command.actorId).admissions)
        assertThat(bounded.entries).hasSize(100)
        assertThat(bounded.truncated).isTrue()
        assertThat(bounded.entries.map { it.admittedAt })
            .containsExactlyElementsOf((1L..100L).map { NOW.minusSeconds(it) })
        assertThat(bounded.entries.map { it.kind }.distinct()).containsExactly(RunExecutionAdmissionKind.RECOVERY)
        assertThat(history.get(command.tenantId, command.runId, command.actorId).admissions).isEqualTo(bounded)
        assertThat(gateway.calls(command.tenantId)).isZero()
    }

    private fun resume(command: ExecuteRunCommand) =
        ResumeRunExecutionCommand(
            command.tenantId,
            command.runId,
            command.actorId,
            command.commandId,
            UUID.randomUUID(),
            command.expectedSupervisionRevision,
            command.expectedRunVersion,
        )

    private fun handover(
        fixture: RunSupervisionDatabaseFixture,
        from: HumanActorId,
        to: HumanActorId,
        revision: Long,
    ) {
        handovers.handover(
            HandoverRunSupervisorCommand(
                fixture.tenant.value,
                fixture.run.value,
                from.value,
                to.value,
                UUID.randomUUID(),
                RunSupervisionRevision(revision),
            ),
        )
    }

    private fun count(
        table: String,
        tenant: UUID,
    ): Int =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
            .param("tenant", tenant)
            .query(Int::class.java)
            .single()

    private fun base(command: ExecuteRunCommand): String {
        val tenantPath = "/bff/v1/tenants/${command.tenantId}"
        return "$tenantPath/resolution-runs/${command.runId}"
    }

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("resolver-${actor.value}")
        }

    @TestConfiguration(proxyBeanMethods = false)
    class HistoryTestConfiguration {
        @Bean @Primary
        fun executionTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean @Primary
        fun executionTestConnector(jdbc: JdbcClient) = RunExecutionTestConnector(jdbc)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-16T08:00:00Z")

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
