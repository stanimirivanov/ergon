package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.adapter.out.connector.FakeIdentityCapabilityConnector
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionRepository
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantService
import org.ergon.controlplane.resolution.application.CapabilityInvocationExecution
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationService
import org.ergon.controlplane.resolution.application.CapabilityInvocationTicker
import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.ResumeRunExecutionCommand
import org.ergon.controlplane.resolution.application.RunExecutionAcknowledgement
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionService
import org.ergon.controlplane.resolution.application.RunExecutionCommandRepository
import org.ergon.controlplane.resolution.application.RunExecutionConflictException
import org.ergon.controlplane.resolution.application.RunExecutionDisabledException
import org.ergon.controlplane.resolution.application.RunExecutionPrerequisites
import org.ergon.controlplane.resolution.application.RunExecutionService
import org.ergon.controlplane.resolution.application.RunExecutionStatus
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.UncertainCapabilityConnectorGateway
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.execution.enabled=true",
        "ergon.run-supervision.handover.enabled=true",
        "spring.flyway.target=20261015120000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunExecutionIntegrationTest.ExecutionTestConfiguration::class)
@Suppress("LongParameterList") // Real ports prove transaction and authority boundaries.
class RunExecutionIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val executions: RunExecutionService,
    @Autowired private val requests: ApprovalRequestService,
    @Autowired private val decisions: ApprovalDecisionService,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val authorities: HumanAuthorityRepository,
    @Autowired private val runs: ResolutionRunRepository,
    @Autowired private val states: ResolutionRunTransitionRepository,
    @Autowired private val prerequisites: RunExecutionPrerequisites,
    @Autowired private val commands: RunExecutionCommandRepository,
    @Autowired private val consumptions: CapabilityAuthorizationConsumptionRepository,
    @Autowired private val receipts: CapabilityInvocationReceiptRepository,
    @Autowired private val results: ResolutionRunCapabilityResultService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val gateway: RunExecutionTestConnector,
    @Autowired private val flyway: Flyway,
) {
    @BeforeEach
    fun upgradeWithExistingApprovedGrant() =
        synchronized(postgres) {
            if (flyway
                    .info()
                    .current()
                    .version
                    .toString() == "20261015120000"
            ) {
                val command = seed().second
                val grant = prerequisites.grants.authorize(command.tenantId, command.decisionId)
                val before = grantCount(command.tenantId)
                val oldExecuted = seed().second
                val oldGrant = prerequisites.grants.authorize(oldExecuted.tenantId, oldExecuted.decisionId)
                val oldConsumption = prerequisites.consumptions.consume(oldExecuted.tenantId, oldGrant.grant.id.value)
                val oldInvocation =
                    CapabilityInvocationService(
                        consumptions,
                        receipts,
                        FakeIdentityCapabilityConnector(),
                        transactions,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        CapabilityInvocationTicker { 0L },
                    ).invoke(oldExecuted.tenantId, oldConsumption.consumption.id.value)
                results.record(oldExecuted.tenantId, oldExecuted.runId)
                Flyway
                    .configure()
                    .configuration(flyway.configuration)
                    .target("latest")
                    .load()
                    .migrate()
                assertThat(grantCount(command.tenantId)).isEqualTo(before)
                assertThat(receipts.find(TenantId(oldExecuted.tenantId), oldConsumption.consumption.id))
                    .isEqualTo(oldInvocation.storedReceipt)
                assertThat(
                    commands.findByRun(
                        TenantId(command.tenantId),
                        ResolutionRunId(command.runId),
                    ),
                ).isNull()
                assertThatThrownBy { executions.execute(command) }
                    .isInstanceOf(RunExecutionConflictException::class.java)
                assertThat(gateway.calls(command.tenantId)).isZero()
                assertThat(grant.grant.id).isNotNull()
            }
        }

    @Test
    fun `confirmed execution commits before IO and exact replay preserves one historical result`() {
        val (fixture, command) = seed()
        mvc
            .perform(get(base(command) + "/execution-review").with(session(fixture.outgoing)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canExecute").value(true))
        val first =
            postCommand(command)
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.resultingState").value("VERIFYING"))
                .andExpect(jsonPath("$.runSequence").value(1))
                .andExpect(jsonPath("$.actorId").doesNotExist())
                .andExpect(jsonPath("$.authorizationConsumptionId").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertThat(
            postCommand(command)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        assertThat(grantCount(command.tenantId)).isEqualTo(1)
        assertThat(count("capability_authorization_consumptions", command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_run_execution_attempts", command.tenantId)).isEqualTo(1)
        assertThat(
            jdbc
                .sql("SELECT status FROM cases WHERE tenant_id = :tenant")
                .param("tenant", command.tenantId)
                .query(String::class.java)
                .single(),
        ).isEqualTo("OPEN")
        for (table in listOf("resolution_run_execution_commands", "resolution_run_execution_attempts")) {
            assertThatThrownBy {
                jdbc.sql("DELETE FROM $table WHERE tenant_id = :tenant").param("tenant", command.tenantId).update()
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `uncertain connector completion requires a new explicit attempt with the same provider key`() {
        val (_, command) = seed()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        gateway.onCall(command.tenantId) { if (calls.getAndIncrement() == 0) error("synthetic lost acknowledgement") }
        val before = gateway.operationCount(command.tenantId)
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.INCOMPLETE)
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.INCOMPLETE)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        val recovery = resume(command)
        val late = serviceAt(NOW.plusSeconds(901))
        assertThat(late.resume(recovery).status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(late.resume(recovery).status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(2)
        assertThat(gateway.operationCount(command.tenantId) - before).isEqualTo(1)
        assertThat(count("capability_authorization_consumptions", command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_run_execution_attempts", command.tenantId)).isEqualTo(2)
    }

    @Test
    fun `a committed reservation survives process loss before invocation without replay silently dispatching`() {
        val (_, command) = seed()
        assertThat(admissionsAt().admit(command).dispatch).isTrue()
        assertThat(gateway.calls(command.tenantId)).isZero()
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.INCOMPLETE)
        assertThat(gateway.calls(command.tenantId)).isZero()
        assertThat(executions.resume(resume(command)).status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `a recorded receipt without projection is repaired without repeating connector IO`() {
        val (_, command) = seed()
        val admitted = admissionsAt().admit(command)
        invocationAt().invoke(command.tenantId, admitted.stored.consumptionId.value)
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.RESULT_RECORDED)
        assertThat(executions.resume(resume(command)).status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `handover denies dispatch and foreign adoption while handback requires a fresh recovery epoch`() {
        val (fixture, command) = seed()
        admissionsAt().admit(command)
        handover(fixture, fixture.outgoing, fixture.incoming, 0)
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.INCOMPLETE)
        assertThatThrownBy { executions.resume(resume(command)) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy { executions.execute(command.copy(actorId = fixture.incoming.value)) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        mvc
            .perform(get(base(command) + "/execution-review").with(session(fixture.incoming)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.commandId").isEmpty)
            .andExpect(jsonPath("$.canResume").value(false))
        handover(fixture, fixture.incoming, fixture.outgoing, 1)
        assertThatThrownBy { executions.resume(resume(command)) }
            .isInstanceOf(RunExecutionConflictException::class.java)
        val fresh = resume(command).copy(expectedSupervisionRevision = RunSupervisionRevision(2))
        assertThat(executions.resume(fresh).status)
            .isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `concurrent duplicate and competing commands cannot admit another provider operation`() {
        val (_, command) = seed()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.onCall(command.tenantId) {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val first =
                executor.submit<RunExecutionAcknowledgement> {
                    executions.execute(command)
                }
            try {
                check(entered.await(10, TimeUnit.SECONDS))
                assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.INCOMPLETE)
                assertThatThrownBy { executions.execute(command.copy(commandId = UUID.randomUUID())) }
                    .isInstanceOf(RunExecutionConflictException::class.java)
            } finally {
                release.countDown()
            }
            assertThat(first.get(20, TimeUnit.SECONDS).status).isEqualTo(RunExecutionStatus.COMPLETED)
        }
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_run_execution_commands", command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `stale review disabled mode expired evidence and changed intent cause no admission or effect`() {
        val (fixture, command) = seed()
        for (invalid in listOf(
            command.copy(expectedSupervisionRevision = RunSupervisionRevision(1)),
            command.copy(decisionId = UUID.randomUUID()),
            command.copy(requestId = UUID.randomUUID()),
        )) {
            assertThatThrownBy { executions.execute(invalid) }.isInstanceOf(RunExecutionConflictException::class.java)
        }
        assertThatThrownBy { admissionsAt(enabled = false).admit(command) }
            .isInstanceOf(RunExecutionDisabledException::class.java)
        assertThatThrownBy { admissionsAt(NOW.plusSeconds(900)).admit(command) }
            .isInstanceOf(RunExecutionConflictException::class.java)
        assertThatThrownBy { admissionsAt(NOW.plusSeconds(3600)).admit(command) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        for (actor in listOf(fixture.other, fixture.expired)) {
            assertThatThrownBy { executions.execute(command.copy(actorId = actor.value)) }
                .isInstanceOf(AssignedRunNotFoundException::class.java)
        }
        assertThat(grantCount(command.tenantId)).isZero()
        assertThat(gateway.calls(command.tenantId)).isZero()
        executions.execute(command)
        assertThatThrownBy { executions.execute(command.copy(decisionId = UUID.randomUUID())) }
            .isInstanceOf(RunExecutionConflictException::class.java)
    }

    @Test
    fun `session CSRF tenant and validation failures are safe and cannot dispatch`() {
        val (fixture, command) = seed()
        mvc.perform(post(base(command) + "/execution-commands")).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(base(command) + "/execution-commands")
                    .with(session(fixture.outgoing))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
        postCommand(command, fixture.other).andExpect(status().isNotFound)
        postCommand(command.copy(tenantId = UUID.randomUUID())).andExpect(status().isForbidden)
        mvc
            .perform(
                post(base(command) + "/execution-commands")
                    .with(session(fixture.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command).replace("\"expectedRunVersion\":0", "\"expectedRunVersion\":1")),
            ).andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-run-execution"))
        assertThat(gateway.calls(command.tenantId)).isZero()
        assertThat(grantCount(command.tenantId)).isZero()
    }

    @Test
    fun `audit foreign key rejection rolls back grant consumption command and attempt before IO`() {
        val (_, command) = seed()
        val broken =
            object : RunExecutionCommandRepository by commands {
                override fun recordAttempt(
                    command: ResumeRunExecutionCommand,
                    supervision: CurrentRunSupervision,
                    evidence: ApprovalAuthorityEvidenceId,
                    admittedAt: Instant,
                ) = commands.recordAttempt(
                    command,
                    supervision,
                    ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                    admittedAt,
                )
            }
        assertThatThrownBy { admissionsAt(repository = broken).admit(command) }
            .isInstanceOf(DataAccessException::class.java)
        for (table in listOf(
            "capability_authorization_grants",
            "capability_authorization_consumptions",
            "resolution_run_execution_commands",
            "resolution_run_execution_attempts",
        )) {
            assertThat(count(table, command.tenantId)).isZero()
        }
        assertThat(gateway.calls(command.tenantId)).isZero()
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.COMPLETED)
    }

    @Test
    fun `rejected prerequisites and absent routes cannot reserve or invoke a capability`() {
        for (command in listOf(seed(outcome = ApprovalDecisionOutcome.REJECTED).second, seed(route = false).second)) {
            assertThatThrownBy { executions.execute(command) }.isInstanceOf(RunExecutionConflictException::class.java)
            assertThat(grantCount(command.tenantId)).isZero()
            assertThat(count("capability_authorization_consumptions", command.tenantId)).isZero()
            assertThat(gateway.calls(command.tenantId)).isZero()
        }
    }

    @Test
    fun `terminal connector failure is recorded truthfully rather than treated as uncertain or verified`() {
        val (_, command) = seed(connector = "identity-stub-failure")
        val result = executions.execute(command)
        assertThat(result.status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(
            result.result
                ?.event
                ?.toState
                ?.name,
        ).isEqualTo("ACTION_FAILED")
        assertThat(
            result.result
                ?.event
                ?.receiptOutcome
                ?.name,
        ).isEqualTo("FAILED")
        assertThat(executions.execute(command)).isEqualTo(result)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_outcome_proof_acceptances", command.tenantId)).isZero()
    }

    @Test
    fun `browser recovery has its own exact replay and stale new attempts cannot dispatch again`() {
        val (_, command) = seed()
        admissionsAt().admit(command)
        val recovery = resume(command)
        val path = base(command) + "/execution-commands/${command.commandId}/attempts"
        val json = """{"attemptId":"${recovery.attemptId}","expectedSupervisionRevision":0,"expectedRunVersion":0}"""
        val first =
            mvc
                .perform(
                    post(path)
                        .with(session(HumanActorId(command.actorId)))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.attemptId").value(recovery.attemptId.toString()))
                .andReturn()
                .response.contentAsString
        val replay =
            mvc
                .perform(
                    post(path)
                        .with(session(HumanActorId(command.actorId)))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(replay).isEqualTo(first)
        mvc
            .perform(
                post(path)
                    .with(session(HumanActorId(command.actorId)))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.replace(recovery.attemptId.toString(), UUID.randomUUID().toString())),
            ).andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `handover after admission cannot cancel an already reserved external effect`() {
        val (fixture, command) = seed()
        gateway.onCall(command.tenantId) { handover(fixture, fixture.outgoing, fixture.incoming, 0) }
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.COMPLETED)
        mvc
            .perform(get(base(command) + "/execution-review").with(session(fixture.outgoing)))
            .andExpect(status().isNotFound)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
    }

    @Test
    fun `a tenant command identity cannot be rebound to another run or actor`() {
        val (_, first) = seed()
        admissionsAt().admit(first)
        val (_, other) = seed(tenant = TenantId(first.tenantId))
        assertThatThrownBy { executions.execute(other.copy(commandId = first.commandId)) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThat(gateway.calls(first.tenantId)).isZero()
        assertThat(count("resolution_run_execution_commands", first.tenantId)).isEqualTo(1)
    }

    @Test
    fun `distinct explicitly admitted concurrent recoveries still address one provider operation`() {
        val (_, command) = seed()
        admissionsAt().admit(command)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        gateway.onCall(command.tenantId) {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
        }
        Executors.newFixedThreadPool(2).use { executor ->
            val tasks =
                List(2) {
                    executor.submit<RunExecutionAcknowledgement> {
                        executions.resume(resume(command))
                    }
                }
            try {
                check(entered.await(10, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
            val results = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results[0]).isEqualTo(results[1])
        }
        assertThat(gateway.calls(command.tenantId)).isEqualTo(2)
        assertThat(gateway.operationCount(command.tenantId)).isEqualTo(1)
        assertThat(count("capability_invocation_receipts", command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_run_execution_attempts", command.tenantId)).isEqualTo(3)
    }

    @Test
    fun `receipt persistence failure propagates without fabricating an outcome or losing admission`() {
        val (_, command) = seed()
        val failingReceipts =
            object : CapabilityInvocationReceiptRepository by receipts {
                override fun createOrFind(
                    tenantId: TenantId,
                    receipt: CapabilityInvocationReceipt,
                ): CapabilityInvocationExecution = throw DataIntegrityViolationException("synthetic write failure")
            }
        val invocation =
            CapabilityInvocationService(
                consumptions,
                failingReceipts,
                UncertainCapabilityConnectorGateway(gateway),
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                CapabilityInvocationTicker { 0L },
            )
        val failing =
            RunExecutionService(
                admissionsAt(),
                invocation,
                results,
                receipts,
                states,
                authorities,
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC),
            )
        assertThatThrownBy { failing.execute(command) }.isInstanceOf(DataAccessException::class.java)
        assertThat(executions.execute(command).status).isEqualTo(RunExecutionStatus.INCOMPLETE)
        assertThat(gateway.calls(command.tenantId)).isEqualTo(1)
        assertThat(count("resolution_run_execution_commands", command.tenantId)).isEqualTo(1)
        assertThat(count("capability_invocation_receipts", command.tenantId)).isZero()
        assertThat(executions.resume(resume(command)).status).isEqualTo(RunExecutionStatus.COMPLETED)
        assertThat(gateway.operationCount(command.tenantId)).isEqualTo(1)
    }

    private fun seed(
        outcome: ApprovalDecisionOutcome = ApprovalDecisionOutcome.APPROVED,
        route: Boolean = true,
        connector: String = "identity-stub",
        tenant: TenantId = TenantId(UUID.randomUUID()),
    ): Pair<RunSupervisionDatabaseFixture, ExecuteRunCommand> {
        val fixture = transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, NOW, tenant = tenant) }
        if (route) {
            jdbc
                .sql(
                    """INSERT INTO tenant_capability_routes (tenant_id, capability, connector)
            VALUES (:tenant, 'identity.account.unlock', :connector) ON CONFLICT DO NOTHING""",
                ).param("tenant", fixture.tenant.value)
                .param("connector", connector)
                .update()
        }
        val request = requests.request(fixture.tenant.value, fixture.run.value).stored.request
        val decision =
            decisions
                .decide(
                    fixture.tenant.value,
                    request.id.value,
                    fixture.outgoing.value,
                    outcome,
                ).decision
        return fixture to
            ExecuteRunCommand(
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

    private fun admissionsAt(
        at: Instant = NOW,
        enabled: Boolean = true,
        repository: RunExecutionCommandRepository = commands,
    ) = RunExecutionAdmissionService(
        assignments,
        authorities,
        runs,
        states,
        prerequisites,
        repository,
        transactions,
        Clock.fixed(at, ZoneOffset.UTC),
        enabled,
    )

    private fun invocationAt(at: Instant = NOW) =
        CapabilityInvocationService(
            consumptions,
            receipts,
            UncertainCapabilityConnectorGateway(gateway),
            transactions,
            Clock.fixed(at, ZoneOffset.UTC),
            CapabilityInvocationTicker { 0L },
        )

    private fun serviceAt(at: Instant) =
        RunExecutionService(
            admissionsAt(at),
            invocationAt(at),
            results,
            receipts,
            states,
            authorities,
            transactions,
            Clock.fixed(at, ZoneOffset.UTC),
        )

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

    private fun grantCount(tenant: UUID) = count("capability_authorization_grants", tenant)

    private fun base(command: ExecuteRunCommand): String {
        val tenantPath = "/bff/v1/tenants/${command.tenantId}"
        return "$tenantPath/resolution-runs/${command.runId}"
    }

    private fun body(command: ExecuteRunCommand) =
        """
        {"commandId":"${command.commandId}","expectedSupervisionRevision":${command.expectedSupervisionRevision.value},
        "expectedRunVersion":${command.expectedRunVersion},
        "requestId":"${command.requestId}","decisionId":"${command.decisionId}"}
        """.trimIndent()

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("resolver-${actor.value}")
        }

    private fun postCommand(
        command: ExecuteRunCommand,
        actor: HumanActorId = HumanActorId(command.actorId),
    ) = mvc.perform(
        post(base(command) + "/execution-commands")
            .with(session(actor))
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(command)),
    )

    @TestConfiguration(proxyBeanMethods = false)
    class ExecutionTestConfiguration {
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
