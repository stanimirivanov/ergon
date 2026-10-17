package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionPlanner
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRecords
import org.ergon.controlplane.resolution.application.ResolutionRunRetryReviewChangedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.ergon.controlplane.resolution.application.RetrySupervisedRunCommand
import org.ergon.controlplane.resolution.application.RunRetryCommandRepository
import org.ergon.controlplane.resolution.application.RunRetryConflictException
import org.ergon.controlplane.resolution.application.RunRetryDisabledException
import org.ergon.controlplane.resolution.application.RunRetryReviewService
import org.ergon.controlplane.resolution.application.RunRetryService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignment
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentIdentityGenerator
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverUnavailableException
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.controlplane.resolution.application.StoredRunRetryCommand
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.retry.enabled=true",
        "ergon.run-supervision.handover.enabled=true",
        "spring.flyway.target=20261016120000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunRetryIntegrationTest.FixedTime::class)
@Suppress("LongParameterList") // Real transaction, authorization and persistence boundaries are the evidence.
class RunRetryIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val mvc: MockMvc,
    @Autowired private val service: RunRetryService,
    @Autowired private val reviews: RunRetryReviewService,
    @Autowired private val commands: RunRetryCommandRepository,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val records: ResolutionRunRetryRecords,
    @Autowired private val internal: ResolutionRunRetryService,
    @Autowired private val console: AssignedRunConsoleService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired cases: CaseCommandService,
    @Autowired timelines: CaseTimelineRepository,
    @Autowired results: ResolutionRunCapabilityResultService,
    @Autowired private val flyway: Flyway,
) {
    private val fixtures = RunRetryTestFixture(cases, timelines, transactions, jdbc, results)
    private val caseCommands = cases

    @BeforeEach
    fun upgradeReleasedAssignmentsAndRetryHistory() =
        synchronized(postgres) {
            val upgraded =
                Flyway
                    .configure()
                    .configuration(flyway.configuration)
                    .target("latest")
                    .load()
            if (upgraded
                    .info()
                    .current()
                    ?.version
                    ?.version == "20261016120000"
            ) {
                val fixture = fixtures.seed()
                val old = internal.retry(fixture.tenant.value, fixture.run.value, 4, fixture.outgoing.value)
                upgraded.migrate()
                upgraded.validate()
                assertThat(assignments.findByRun(fixture.tenant, fixture.run)).isEqualTo(fixture.initial)
                assertThat(records.retries.find(fixture.tenant, fixture.run)).isEqualTo(old.recording.storedEvent)
                assertThat(assignments.findByRun(fixture.tenant, old.replacementRun.run.id)).isNull()
                assertThat(count("resolution_run_retry_commands", fixture.tenant)).isZero()
            }
        }

    @Test
    fun `review and confirmation create a discoverable fresh successor with exact private replay`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val read =
            mvc
                .perform(get(base(command) + "/retry-review").with(session(command.actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.canRetry").value(true))
                .andExpect(jsonPath("$.maximumAttempts").value(2))
                .andReturn()
                .response.contentAsString
        assertThat(read).doesNotContain("PRIVATE_", "authorityEvidenceId", fixture.incoming.value.toString())
        val first =
            send(command)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .response.contentAsString
        assertThat(
            send(command)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        val stored = checkNotNull(commands.find(fixture.tenant, command.commandId))
        val successor = ResolutionRunId(stored.successorRunId)
        val assigned = checkNotNull(assignments.findByRun(fixture.tenant, successor)).assignment
        assertThat(assigned.assigningMachineSubject).isNull()
        assertThat(assigned.retryCommandId).isEqualTo(command.commandId)
        assertThat(assigned.supervisorActorId).isEqualTo(fixture.outgoing)
        assertThat(assigned.authorityEvidenceId.value).isEqualTo(stored.authorityEvidenceId)
        val detail = console.get(command.tenantId, successor.value, command.actorId)
        assertThat(detail.start.run.attemptNumber).isEqualTo(2)
        assertThat(detail.start.run.predecessorRunId).isEqualTo(fixture.run)
        assertThat(detail.start.run.initialState.name).isEqualTo("WAITING_FOR_APPROVAL")
        assertThat(detail.supervisionRevision.value).isZero()
        assertThat(detail.state.version).isZero()
        assertThat(detail.execution).isNull()
        assertThat(detail.approvalReview.latest).isNull()
        assertThat(console.list(command.tenantId, command.actorId, 30, null).entries.map { it.runId })
            .containsExactly(successor)
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
        val authorizationTables =
            listOf(
                "capability_authorization_grants",
                "capability_authorization_consumptions",
                "resolution_approval_requests",
            )
        for (table in authorizationTables) {
            assertThat(count(table, fixture.tenant)).isZero()
        }
        assertThat(count("capability_invocation_receipts", fixture.tenant)).isEqualTo(1)
        assertThat(first).doesNotContain("authorityEvidenceId", "actorId", "assignmentId", "retryEventId")
    }

    @Test
    fun `stale epoch plan policy or ceiling cannot commit any retry`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val changed =
            listOf(
                command.copy(expectedSupervisionRevision = RunSupervisionRevision(1)),
                command.copy(plan = command.plan.copy(caseStreamVersion = 5)),
                command.copy(plan = command.plan.copy(requiredApproval = ApprovalRequirement.REQUESTER)),
                command.copy(plan = command.plan.copy(policyRevision = "ergon.dev/policy/access-restoration/v2")),
                command.copy(retryPolicyRevision = "ergon.dev/policy/resolution-retry/v2"),
                command.copy(maximumAttempts = 3),
            )
        changed.forEach { assertThatThrownBy { service.retry(it) }.isInstanceOf(RunRetryConflictException::class.java) }
        assertUnchanged(fixture)
    }

    @Test
    fun `new case evidence invalidates reviewed intent rather than silently substituting it`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val start = checkNotNull(records.runs.find(fixture.tenant, fixture.run)).run
        caseCommands.recordConnectorObservation(
            RecordConnectorObservationCommand(
                command.tenantId,
                start.caseId.value,
                command.plan.caseStreamVersion,
                "identity-stub",
                "account",
                "LATER_PRIVATE_EVIDENCE",
            ),
        )
        send(command)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-retry-conflict"))
        assertUnchanged(fixture)
    }

    @Test
    fun `non-supervisors expired authority foreign tenants and anonymous requests fail closed`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        for (actor in listOf(fixture.other, fixture.incoming, fixture.expired)) {
            mvc
                .perform(get(base(command) + "/retry-review").with(session(actor.value)))
                .andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
            send(command.copy(actorId = actor.value)).andExpect(status().isNotFound)
        }
        mvc.perform(get(base(command) + "/retry-review")).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(base(command) + "/retry-commands").contentType(MediaType.APPLICATION_JSON).content(body(command)),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(base(command) + "/retry-commands")
                    .with(session(command.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
        send(command.copy(tenantId = UUID.randomUUID())).andExpect(status().isForbidden)
        send(command, body(command).replace("\"expectedRunVersion\":1", "\"expectedRunVersion\":0"))
            .andExpect(status().isBadRequest)
        assertUnchanged(fixture)
    }

    @Test
    fun `disabled commands and expired replay never acquire authority from an earlier review`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        assertThatThrownBy { serviceAt(enabled = false).retry(command) }
            .isInstanceOf(RunRetryDisabledException::class.java)
        val disabledReview =
            RunRetryReviewService(assignments, records, policy(), transactions, clock(), false)
                .review(command.tenantId, command.runId, command.actorId)
        assertThat(disabledReview.supported).isFalse()
        assertThat(disabledReview.canRetry).isFalse()
        service.retry(command)
        assertThatThrownBy { serviceAt(at = RunRetryTestFixture.NOW.plusSeconds(3601)).retry(command) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    @Test
    fun `handover fences stale confirmation and returned supervisors require a new epoch`() {
        val fixture = fixtures.seed()
        val old = command(fixture)
        handover(fixture, fixture.run, fixture.outgoing, fixture.incoming, 0)
        assertThatThrownBy { service.retry(old) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        handover(fixture, fixture.run, fixture.incoming, fixture.outgoing, 1)
        assertThatThrownBy { service.retry(old) }.isInstanceOf(RunRetryConflictException::class.java)
        assertThat(service.retry(command(fixture)).created).isTrue()
    }

    @Test
    fun `historical replay after successor handover never restores the originating actors access`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val first = service.retry(command).stored
        val successor = ResolutionRunId(first.successorRunId)
        handover(fixture, successor, fixture.outgoing, fixture.incoming, 0)
        assertThat(service.retry(command).stored).isEqualTo(first)
        assertThat(assignments.findAssigned(fixture.tenant, successor, fixture.outgoing, RunRetryTestFixture.NOW))
            .isNull()
        assertThat(assignments.findAssigned(fixture.tenant, successor, fixture.incoming, RunRetryTestFixture.NOW))
            .isNotNull()
        assertThatThrownBy { service.retry(command.copy(actorId = fixture.incoming.value)) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThatThrownBy { service.retry(command.copy(maximumAttempts = 3)) }
            .isInstanceOf(RunRetryConflictException::class.java)
    }

    @Test
    fun `internal retry cannot be adopted or given browser successor supervision`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val existing = internal.retry(command.tenantId, command.runId, command.plan.caseStreamVersion, command.actorId)
        assertThatThrownBy { service.retry(command) }.isInstanceOf(RunRetryConflictException::class.java)
        assertThat(assignments.findByRun(fixture.tenant, existing.replacementRun.run.id)).isNull()
        assertThat(count("resolution_run_retry_commands", fixture.tenant)).isZero()
    }

    @Test
    fun `receipt persistence failure rolls back runtime transition successor assignment and access projection`() {
        val fixture = fixtures.seed()
        val failing =
            object : RunRetryCommandRepository by commands {
                override fun create(
                    tenantId: TenantId,
                    command: StoredRunRetryCommand,
                ): StoredRunRetryCommand = throw DataIntegrityViolationException("synthetic receipt failure")
            }
        assertThatThrownBy { serviceAt(repository = failing).retry(command(fixture)) }
            .isInstanceOf(DataAccessException::class.java)
        assertUnchanged(fixture)
        assertThat(count("resolution_run_current_supervision", fixture.tenant)).isEqualTo(1)
        assertThat(count("resolution_run_supervision_events", fixture.tenant)).isEqualTo(1)
    }

    @Test
    fun `command audit is immutable and browser assignment cannot commit without its exact command`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val first = service.retry(command).stored
        for (statement in listOf(
            "UPDATE resolution_run_retry_commands SET maximum_attempts = 3 WHERE tenant_id = :tenant",
            "DELETE FROM resolution_run_retry_commands WHERE tenant_id = :tenant",
        )) {
            assertThatThrownBy { jdbc.sql(statement).param("tenant", fixture.tenant.value).update() }
                .isInstanceOf(DataAccessException::class.java)
        }
        val other = fixtures.seed()
        val internalRetry = internal.retry(other.tenant.value, other.run.value, 4, other.outgoing.value)
        assertThatThrownBy {
            transactions.required {
                val orphan = UUID.randomUUID()
                assignments.create(
                    other.tenant,
                    RunSupervisorAssignment(
                        UUID.randomUUID(),
                        internalRetry.replacementRun.run.id,
                        other.outgoing,
                        other.outgoingEvidence,
                        orphan,
                        null,
                        RunRetryTestFixture.NOW,
                        orphan,
                    ),
                )
            }
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(assignments.findByRun(other.tenant, internalRetry.replacementRun.run.id)).isNull()
        assertThat(first.authorityEvidenceId).isEqualTo(fixture.outgoingEvidence.value)
    }

    @Test
    fun `concurrent exact commands return one identical durable result`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val results = together({ service.retry(command).stored }, { service.retry(command).stored })
        assertThat(results[0]).isEqualTo(results[1])
        assertThat(count("resolution_run_retry_commands", fixture.tenant)).isEqualTo(1)
        assertThat(count("resolution_run_supervisor_assignments", fixture.tenant)).isEqualTo(2)
    }

    @Test
    fun `distinct concurrent confirmations create one successor and one conflict`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val failures =
            together(
                { catchThrowable { service.retry(command) } },
                { catchThrowable { service.retry(command.copy(commandId = UUID.randomUUID())) } },
            )
        assertThat(failures.count { it == null }).isEqualTo(1)
        assertThat(failures.filterNotNull().single()).isInstanceOf(RunRetryConflictException::class.java)
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
    }

    @Test
    fun `browser and internal retry racing still commit only one successor without adopting internal supervision`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val failures =
            together(
                { catchThrowable { service.retry(command) } },
                {
                    catchThrowable {
                        internal.retry(command.tenantId, command.runId, command.plan.caseStreamVersion, command.actorId)
                    }
                },
            )
        assertThat(failures[1]).isNull()
        assertThat(failures[0] == null || failures[0] is RunRetryConflictException).isTrue()
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
        assertThat(count("resolution_run_supervisor_assignments", fixture.tenant))
            .isEqualTo(if (failures[0] == null) 2 else 1)
    }

    private fun command(fixture: RunSupervisionDatabaseFixture): RetrySupervisedRunCommand {
        val review = reviews.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        return RetrySupervisedRunCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(review.supervisionRevision),
            review.runVersion,
            review.retryPolicyRevision,
            review.maximumAttempts,
            checkNotNull(review.plan),
        )
    }

    @Test
    fun `runtime re-evaluation cannot silently replace the reviewed policy plan`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val changingPlan =
            ResolutionPlanner { tenant, case ->
                records.planning.plan(tenant, case).copy(
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v2"),
                )
            }
        val runtime =
            ResolutionRunRetryService(
                records.copy(planning = changingPlan),
                ResolutionRunIdentityGenerator { error("changed plan must not allocate") },
                ResolutionRunEventIdentityGenerator { error("changed plan must not allocate") },
                policy(),
                transactions,
                clock(),
            )
        assertThatThrownBy { serviceAt(runtime = runtime).retry(command) }
            .isInstanceOf(ResolutionRunRetryReviewChangedException::class.java)
        assertUnchanged(fixture)
    }

    @Test
    fun `authority expiring during case locking rolls back a newly written runtime successor`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val time = MutableRetryClock(RunRetryTestFixture.NOW)
        val delayedRuns =
            object : ResolutionRunRepository by records.runs {
                override fun createRetry(
                    tenantId: TenantId,
                    run: ResolutionRunStart,
                ): StoredResolutionRunStart =
                    records.runs.createRetry(tenantId, run).also {
                        time.at = RunRetryTestFixture.NOW.plusSeconds(3601)
                    }
            }
        val runtime =
            ResolutionRunRetryService(
                records.copy(runs = delayedRuns),
                ResolutionRunIdentityGenerator { ResolutionRunId(UUID.randomUUID()) },
                ResolutionRunEventIdentityGenerator {
                    ResolutionRunEventId(UUID.randomUUID())
                },
                policy(),
                transactions,
                time,
            )
        assertThatThrownBy { serviceAt(runtime = runtime, executionClock = time).retry(command) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertUnchanged(fixture)
    }

    private class MutableRetryClock(
        var at: Instant,
    ) : Clock() {
        override fun instant(): Instant = at

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(at, zone)
    }

    @Test
    fun `failed second attempt exposes exhausted policy without creating a third run or escalating`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val successor = ResolutionRunId(service.retry(command).stored.successorRunId)
        fixtures.failRun(fixture.tenant, successor)
        val review = reviews.review(command.tenantId, successor.value, command.actorId)
        assertThat(review.attemptNumber).isEqualTo(2)
        assertThat(review.maximumAttempts).isEqualTo(2)
        assertThat(review.canRetry).isFalse()
        assertThat(review.reason?.name).isEqualTo("ATTEMPT_LIMIT_REACHED")
        assertThat(review.plan).isNull()
        assertThatThrownBy { service.retry(command.copy(runId = successor.value, commandId = UUID.randomUUID())) }
            .isInstanceOf(RunRetryConflictException::class.java)
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
        assertThat(count("resolution_run_retry_commands", fixture.tenant)).isEqualTo(1)
        assertThat(
            records.transitions
                .findState(fixture.tenant, successor)
                ?.state
                ?.name,
        ).isEqualTo("ACTION_FAILED")
    }

    @Test
    fun `retry and predecessor handover racing permit only one decision under the original epoch`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        val failures =
            together(
                { catchThrowable { service.retry(command) } },
                { catchThrowable { handover(fixture, fixture.run, fixture.outgoing, fixture.incoming, 0) } },
            )
        assertThat(failures.count { it == null }).isEqualTo(1)
        if (failures[0] == null) {
            assertThat(
                failures[1],
            ).isInstanceOf(RunSupervisorHandoverUnavailableException::class.java)
            assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
        } else {
            assertThat(failures[0]).isInstanceOf(AssignedRunNotFoundException::class.java)
            assertUnchanged(fixture)
        }
    }

    @Test
    fun `command identity cannot be rebound to another supervised run in the same tenant`() {
        val fixture = fixtures.seed()
        val command = command(fixture)
        service.retry(command)
        // The successor has its own supervision gate, but knowing its identity
        // cannot make an old command authorize a new retry or expose new facts.
        val successor = checkNotNull(commands.find(fixture.tenant, command.commandId)).successorRunId
        assertThatThrownBy { service.retry(command.copy(runId = successor)) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
        assertThat(count("resolution_run_retry_commands", fixture.tenant)).isEqualTo(1)
    }

    private fun assertUnchanged(fixture: RunSupervisionDatabaseFixture) {
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(1)
        assertThat(count("resolution_run_retry_commands", fixture.tenant)).isZero()
        assertThat(count("resolution_run_supervisor_assignments", fixture.tenant)).isEqualTo(1)
        assertThat(
            records.transitions
                .findState(fixture.tenant, fixture.run)
                ?.state
                ?.name,
        ).isEqualTo("ACTION_FAILED")
    }

    private fun handover(
        fixture: RunSupervisionDatabaseFixture,
        run: ResolutionRunId,
        from: HumanActorId,
        to: HumanActorId,
        revision: Long,
    ) {
        handovers.handover(
            HandoverRunSupervisorCommand(
                fixture.tenant.value,
                run.value,
                from.value,
                to.value,
                UUID.randomUUID(),
                RunSupervisionRevision(revision),
            ),
        )
    }

    private fun serviceAt(
        at: Instant = RunRetryTestFixture.NOW,
        enabled: Boolean = true,
        repository: RunRetryCommandRepository = commands,
        runtime: ResolutionRunRetryService = internal,
        executionClock: Clock = clock(at),
    ) = RunRetryService(
        assignments,
        repository,
        records,
        reviews,
        runtime,
        RunSupervisorAssignmentIdentityGenerator { UUID.randomUUID() },
        transactions,
        executionClock,
        enabled,
    )

    private fun clock(at: Instant = RunRetryTestFixture.NOW) = Clock.fixed(at, ZoneOffset.UTC)

    private fun policy() =
        ResolutionRetryPolicy.define(
            ResolutionRetryPolicyRevision
                .of("ergon.dev/policy/resolution-retry/v1"),
            2,
        )

    private fun count(
        table: String,
        tenant: TenantId,
    ): Int =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
            .param("tenant", tenant.value)
            .query(Int::class.java)
            .single()

    private fun session(actor: UUID) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test").subject("resolver-$actor")
        }

    private fun base(command: RetrySupervisedRunCommand): String =
        "/bff/v1/tenants/${command.tenantId}" +
            "/resolution-runs/${command.runId}"

    private fun body(command: RetrySupervisedRunCommand): String {
        val plan = command.plan
        return """{"commandId":"${command.commandId}","expectedSupervisionRevision":${command.expectedSupervisionRevision.value},
            "expectedRunVersion":${command.expectedRunVersion},"retryPolicyRevision":"${command.retryPolicyRevision}",
            "maximumAttempts":${command.maximumAttempts},"plan":{"caseStreamVersion":${plan.caseStreamVersion},
            "contractKey":"${plan.contractKey}","contractRevision":${plan.contractRevision},"policyRevision":"${plan.policyRevision}",
            "stepId":"${plan.stepId}","capability":"${plan.capability}","effectiveRisk":"${plan.effectiveRisk}",
            "requiredApproval":"${plan.requiredApproval}"}}"""
    }

    private fun send(
        command: RetrySupervisedRunCommand,
        content: String = body(command),
    ) = mvc.perform(
        post(base(command) + "/retry-commands")
            .with(session(command.actorId))
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content),
    )

    private fun <T> together(
        first: () -> T,
        second: () -> T,
    ): List<T> =
        Executors.newFixedThreadPool(2).use { pool ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val tasks =
                listOf(first, second).map { action ->
                    pool.submit<T> {
                        ready.countDown()
                        check(start.await(15, TimeUnit.SECONDS))
                        action()
                    }
                }
            try {
                check(ready.await(15, TimeUnit.SECONDS))
            } finally {
                start.countDown()
            }
            tasks.map { it.get(30, TimeUnit.SECONDS) }
        }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun retryClock(): Clock = Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC)
    }

    companion object {
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
