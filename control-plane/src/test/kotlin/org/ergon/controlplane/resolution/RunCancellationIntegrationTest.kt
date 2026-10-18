package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture.session
import org.ergon.controlplane.resolution.RunEscalationTestFixture.together
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CancelSupervisedRunCommand
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantService
import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunCancelledException
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.RunCancellationConflictException
import org.ergon.controlplane.resolution.application.RunCancellationDisabledException
import org.ergon.controlplane.resolution.application.RunCancellationRecords
import org.ergon.controlplane.resolution.application.RunCancellationRepository
import org.ergon.controlplane.resolution.application.RunCancellationService
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.StoredRunCancellation
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
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
import org.springframework.dao.DataAccessException
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
        "ergon.security.browser-session.enabled=true", "ergon.run-supervision.cancellation.enabled=true",
        "ergon.run-supervision.execution.enabled=true", "ergon.run-supervision.handover.enabled=true",
        "spring.flyway.target=20261017120000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
@Import(BrowserSessionTestClientConfiguration::class, RunCancellationIntegrationTest.FixedTime::class)
@Suppress("LongParameterList") // Real application, JDBC and HTTP boundaries are the security/concurrency evidence.
class RunCancellationIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val service: RunCancellationService,
    @Autowired private val records: RunCancellationRecords,
    @Autowired private val identities: ResolutionRunEventIdentityGenerator,
    @Autowired private val requests: ApprovalRequestService,
    @Autowired private val decisions: ApprovalDecisionService,
    @Autowired private val grants: CapabilityAuthorizationGrantService,
    @Autowired private val admissions: RunExecutionAdmissionService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val flyway: Flyway,
) {
    @BeforeEach
    fun upgradeReleasedHistory() =
        synchronized(postgres) {
            if (flyway
                    .info()
                    .current()
                    .version
                    .toString() == "20261017120000"
            ) {
                val fixture = seed()
                val execution = approved(fixture)
                val grant = grants.authorize(execution.tenantId, execution.decisionId)
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .table("ergon_flyway_schema_history")
                    .load()
                    .migrate()
                assertThat(records.cancellations.hasExecutionHistory(fixture.tenant, fixture.run)).isTrue()
                assertThatThrownBy { service.cancel(command(fixture)) }
                    .isInstanceOf(RunCancellationConflictException::class.java)
                assertThat(
                    jdbc
                        .sql(
                            """
                            SELECT authorization_grant_id FROM capability_authorization_grants
                            WHERE tenant_id = :tenant
                            """.trimIndent(),
                        ).param("tenant", fixture.tenant.value)
                        .query(UUID::class.java)
                        .single(),
                ).isEqualTo(grant.grant.id.value)
            }
        }

    @Test
    fun `confirmation and exact uncertain replay record one immutable terminal fact without execution`() {
        val fixture = seed()
        val command = command(fixture)
        val base = base(command)
        mvc
            .perform(get("$base/cancellation-review").with(session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.canCancel").value(true))
            .andExpect(jsonPath("$.runVersion").value(0))
        send(command)
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.reason").value("OPERATOR_REQUESTED"))
            .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
            .andExpect(jsonPath("$.actorId").doesNotExist())
        send(command).andExpect(status().isOk)
        assertThat(service.cancel(command).created).isFalse()
        assertThat(count("resolution_run_events", fixture)).isEqualTo(1)
        assertThat(count("resolution_run_cancellation_commands", fixture)).isEqualTo(1)
        assertThat(count("capability_authorization_grants", fixture)).isZero()
        assertThat(count("capability_invocation_receipts", fixture)).isZero()
        assertThat(count("human_follow_up_work_items", fixture)).isZero()
        assertThat(state(fixture)).isEqualTo("CANCELLED")
        assertThat(
            jdbc
                .sql("SELECT status FROM cases WHERE tenant_id = :tenant")
                .param("tenant", command.tenantId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("OPEN")
        mvc
            .perform(get("$base/console").with(session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("CANCELLED"))
            .andExpect(jsonPath("$.transitions[0].type").value("RUN_CANCELLED"))
        mvc
            .perform(get("/bff/v1/tenants/${command.tenantId}/resolution-runs/assigned").with(session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries").isEmpty())
        assertThatThrownBy {
            jdbc
                .sql("DELETE FROM resolution_run_cancellation_commands WHERE tenant_id = :tenant")
                .param("tenant", command.tenantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `pending approval history is retained but cannot acquire decisions or grants after cancellation`() {
        val pending = seed()
        val request = requests.request(pending.tenant.value, pending.run.value).stored.request
        service.cancel(command(pending))
        assertThatThrownBy {
            decisions.decide(
                pending.tenant.value,
                request.id.value,
                pending.outgoing.value,
                ApprovalDecisionOutcome.APPROVED,
            )
        }.isInstanceOf(ResolutionRunCancelledException::class.java)
        assertThatThrownBy { requests.request(pending.tenant.value, pending.run.value) }
            .isInstanceOf(ResolutionRunCancelledException::class.java)
        assertThat(requests.get(pending.tenant.value, request.id.value).stored.request).isEqualTo(request)
        val approved = seed()
        val execution = approved(approved)
        service.cancel(command(approved))
        assertThatThrownBy { grants.authorize(execution.tenantId, execution.decisionId) }
            .isInstanceOf(ResolutionRunCancelledException::class.java)
        assertThatThrownBy { admissions.admit(execution) }.isInstanceOf(RuntimeException::class.java)
        assertThat(count("resolution_approval_decisions", approved)).isEqualTo(1)
        assertThat(count("capability_authorization_grants", approved)).isZero()
    }

    @Test
    fun `unconsumed legacy grant and browser reservation both deny cancellation at version zero`() {
        val legacy = seed()
        val grantIntent = approved(legacy)
        grants.authorize(grantIntent.tenantId, grantIntent.decisionId)
        assertThatThrownBy { service.cancel(command(legacy)) }
            .isInstanceOf(RunCancellationConflictException::class.java)
        val browser = seed()
        admissions.admit(approved(browser))
        assertThatThrownBy { service.cancel(command(browser)) }
            .isInstanceOf(RunCancellationConflictException::class.java)
        assertThat(count("resolution_run_events", browser)).isZero()
        assertThat(service.review(browser.tenant.value, browser.run.value, browser.outgoing.value).reason.name)
            .isEqualTo("AUTHORIZATION_OR_EXECUTION_RECORDED")
    }

    @Test
    fun `cancellation and legacy authorization have one consistent winner`() {
        val fixture = seed()
        val intent = approved(fixture)
        val outcomes =
            together(
                { catchThrowable { service.cancel(command(fixture)) } },
                { catchThrowable { grants.authorize(intent.tenantId, intent.decisionId) } },
            )
        assertThat(outcomes.count { it == null }).isEqualTo(1)
        assertWinner(fixture)
    }

    @Test
    fun `cancellation and browser admission have one consistent winner without connector IO`() {
        val fixture = seed()
        val intent = approved(fixture)
        val outcomes =
            together(
                { catchThrowable { service.cancel(command(fixture)) } },
                { catchThrowable { admissions.admit(intent) } },
            )
        assertThat(outcomes.count { it == null }).isEqualTo(1)
        assertWinner(fixture)
        assertThat(count("capability_invocation_receipts", fixture)).isZero()
    }

    @Test
    fun `rollback after event projection and audit leaves no cancellation and can explicitly retry`() {
        val fixture = seed()
        val failing =
            object : RunCancellationRepository by records.cancellations {
                override fun append(
                    tenantId: TenantId,
                    cancellation: StoredRunCancellation,
                ): StoredRunCancellation {
                    records.cancellations.append(tenantId, cancellation)
                    error("synthetic failure after all writes")
                }
            }
        val useCase = serviceAt(records.copy(cancellations = failing))
        val command = command(fixture)
        assertThatThrownBy { useCase.cancel(command) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(state(fixture)).isEqualTo("WAITING_FOR_APPROVAL")
        assertThat(count("resolution_run_events", fixture)).isZero()
        assertThat(count("resolution_run_cancellation_commands", fixture)).isZero()
        assertThat(service.cancel(command).created).isTrue()
    }

    @Test
    fun `handover stales first application but historical receipt does not consult continued supervision`() {
        val fixture = seed()
        val original = command(fixture)
        handovers.handover(
            HandoverRunSupervisorCommand(
                original.tenantId,
                original.runId,
                original.actorId,
                fixture.incoming.value,
                UUID.randomUUID(),
                original.expectedSupervisionRevision,
            ),
        )
        assertThatThrownBy { service.cancel(original) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        val current =
            original.copy(
                actorId = fixture.incoming.value,
                expectedSupervisionRevision = RunSupervisionRevision(1),
            )
        val first = service.cancel(current).stored
        val historyOnly =
            object : RunSupervisorAssignmentRepository by records.assignments {
                override fun findAssigned(
                    tenantId: TenantId,
                    runId: ResolutionRunId,
                    actorId: HumanActorId,
                    at: Instant,
                ): CurrentRunSupervision? = error("replay cannot restore access")
            }
        assertThat(serviceAt(records.copy(assignments = historyOnly)).cancel(current).stored).isEqualTo(first)
        assertThatThrownBy { service.cancel(current.copy(expectedSupervisionRevision = RunSupervisionRevision(0))) }
            .isInstanceOf(RunCancellationConflictException::class.java)
        send(current.copy(actorId = fixture.outgoing.value)).andExpect(status().isNotFound)
    }

    @Test
    fun `disabled foreign expired malformed session and CSRF cases fail closed`() {
        val fixture = seed()
        val original = command(fixture)
        val disabled = serviceAt(enabled = false)
        assertThat(disabled.review(original.tenantId, original.runId, original.actorId).reason.name)
            .isEqualTo("DISABLED")
        assertThatThrownBy { disabled.cancel(original) }.isInstanceOf(RunCancellationDisabledException::class.java)
        for (actor in listOf(fixture.other, fixture.expired, fixture.incoming)) {
            send(original.copy(actorId = actor.value)).andExpect(status().isNotFound)
        }
        send(original.copy(runId = UUID.randomUUID())).andExpect(status().isNotFound)
        send(original.copy(tenantId = UUID.randomUUID())).andExpect(status().isForbidden)
        send(original.copy(expectedSupervisionRevision = RunSupervisionRevision(1))).andExpect(status().isConflict)
        mvc.perform(get(base(original) + "/cancellation-review")).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(base(original) + "/cancellation-commands")
                    .with(session(original.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(original)),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post(base(original) + "/cancellation-commands")
                    .with(session(original.actorId))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"commandId\":\"invalid\"}"),
            ).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
        assertThat(count("resolution_run_events", fixture)).isZero()
        val expired = serviceAt(at = NOW.plusSeconds(3600))
        assertThatThrownBy { expired.cancel(original) }.isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    private fun assertWinner(fixture: RunSupervisionDatabaseFixture) {
        val cancelled = state(fixture) == "CANCELLED"
        assertThat(count("capability_authorization_grants", fixture)).isEqualTo(if (cancelled) 0 else 1)
        assertThat(count("resolution_run_cancellation_commands", fixture)).isEqualTo(if (cancelled) 1 else 0)
    }

    @Test
    fun `ready without human approval is equally cancellable and terminal commands cannot run again`() {
        val fixture =
            transactions.required {
                RunSupervisionDatabaseFixture.seed(jdbc, NOW, org.ergon.contracts.domain.ApprovalRequirement.NONE)
            }
        assertThat(service.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).canCancel).isTrue()
        service.cancel(command(fixture))
        assertThatThrownBy { service.cancel(command(fixture)) }
            .isInstanceOf(RunCancellationConflictException::class.java)
        assertThat(
            service.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).reason.name,
        ).isEqualTo("RUN_NOT_UNTOUCHED")
    }

    @Test
    fun `resolver authority is rechecked after the run lock boundary and on historical replay`() {
        val fixture = seed()
        val instant =
            java.util.concurrent.atomic
                .AtomicReference(NOW)
        val clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId): Clock = this

                override fun instant(): Instant = instant.get()
            }
        val afterWait =
            object : RunSupervisorAssignmentRepository by records.assignments {
                override fun lockRun(
                    tenantId: TenantId,
                    runId: ResolutionRunId,
                ): Boolean {
                    val found = records.assignments.lockRun(tenantId, runId)
                    // Deterministic elapsed lock time: no wall clock or sleep in this authority test.
                    instant.set(NOW.plusSeconds(3600))
                    return found
                }
            }
        val delayed =
            RunCancellationService(
                records.copy(assignments = afterWait),
                identities,
                transactions,
                clock,
                true,
            )
        val command = command(fixture)
        assertThatThrownBy { delayed.cancel(command) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThat(count("resolution_run_events", fixture)).isZero()
        service.cancel(command)
        assertThatThrownBy { delayed.cancel(command) }.isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    private fun seed() = transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, NOW) }

    private fun approved(fixture: RunSupervisionDatabaseFixture): ExecuteRunCommand {
        jdbc
            .sql(
                """
                INSERT INTO tenant_capability_routes (tenant_id, capability, connector)
                VALUES (:tenant, 'identity.account.unlock', 'identity-stub')
                """.trimIndent(),
            ).param("tenant", fixture.tenant.value)
            .update()
        val request = requests.request(fixture.tenant.value, fixture.run.value).stored.request
        val decision =
            decisions
                .decide(
                    fixture.tenant.value,
                    request.id.value,
                    fixture.outgoing.value,
                    ApprovalDecisionOutcome.APPROVED,
                ).decision
        return ExecuteRunCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            0,
            request.id.value,
            decision.id.value,
        )
    }

    private fun command(fixture: RunSupervisionDatabaseFixture) =
        CancelSupervisedRunCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            0,
        )

    private fun state(fixture: RunSupervisionDatabaseFixture) =
        jdbc
            .sql("SELECT state FROM resolution_run_states WHERE tenant_id = :tenant AND run_id = :run")
            .param("tenant", fixture.tenant.value)
            .param("run", fixture.run.value)
            .query(String::class.java)
            .single()

    private fun count(
        table: String,
        fixture: RunSupervisionDatabaseFixture,
    ) = jdbc
        .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
        .param("tenant", fixture.tenant.value)
        .query(Int::class.java)
        .single()

    private fun serviceAt(
        sources: RunCancellationRecords = records,
        enabled: Boolean = true,
        at: Instant = NOW,
    ) = RunCancellationService(sources, identities, transactions, Clock.fixed(at, ZoneOffset.UTC), enabled)

    private fun base(command: CancelSupervisedRunCommand): String {
        val tenantPath = "/bff/v1/tenants/${command.tenantId}"
        return "$tenantPath/resolution-runs/${command.runId}"
    }

    private fun body(command: CancelSupervisedRunCommand) =
        """{"commandId":"${command.commandId}",
        "expectedSupervisionRevision":${command.expectedSupervisionRevision.value},
        "expectedRunVersion":${command.expectedRunVersion}}"""

    private fun send(command: CancelSupervisedRunCommand) =
        mvc.perform(
            post(base(command) + "/cancellation-commands")
                .with(session(command.actorId))
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(command)),
        )

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun cancellationTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-18T12:00:00Z")

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
