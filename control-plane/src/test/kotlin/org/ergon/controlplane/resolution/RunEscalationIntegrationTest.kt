package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.RunEscalationTestFixture.base
import org.ergon.controlplane.resolution.RunEscalationTestFixture.body
import org.ergon.controlplane.resolution.RunEscalationTestFixture.policy
import org.ergon.controlplane.resolution.RunEscalationTestFixture.session
import org.ergon.controlplane.resolution.RunEscalationTestFixture.together
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.EscalateSupervisedRunCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationIdentityGenerators
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRecords
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationReviewChangedException
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationService
import org.ergon.controlplane.resolution.application.ResolutionRunRetryLimitReachedException
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.ergon.controlplane.resolution.application.ResolutionRunRetryStateException
import org.ergon.controlplane.resolution.application.RunEscalationCommandRepository
import org.ergon.controlplane.resolution.application.RunEscalationConflictException
import org.ergon.controlplane.resolution.application.RunEscalationDisabledException
import org.ergon.controlplane.resolution.application.RunEscalationReviewService
import org.ergon.controlplane.resolution.application.RunEscalationService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverUnavailableException
import org.ergon.controlplane.resolution.application.StoredRunEscalationCommand
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationReason
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
        "ergon.security.browser-session.enabled=true", "ergon.run-supervision.escalation.enabled=true",
        "ergon.run-supervision.handover.enabled=true", "spring.flyway.target=20261016200000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
@Import(BrowserSessionTestClientConfiguration::class, RunEscalationIntegrationTest.FixedTime::class)
@Suppress("LongParameterList") // Real HTTP, transaction, authorization and migration boundaries are the evidence.
class RunEscalationIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val controlGate: org.ergon.controlplane.resolution.application.RunCommandControlGate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val service: RunEscalationService,
    @Autowired private val reviews: RunEscalationReviewService,
    @Autowired private val commands: RunEscalationCommandRepository,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired private val records: ResolutionRunEscalationRecords,
    @Autowired private val runtime: ResolutionRunEscalationService,
    @Autowired private val identities: ResolutionRunEscalationIdentityGenerators,
    @Autowired private val retries: ResolutionRunRetryService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired cases: CaseCommandService,
    @Autowired timelines: CaseTimelineRepository,
    @Autowired results: ResolutionRunCapabilityResultService,
    @Autowired private val flyway: Flyway,
    @Autowired private val claims: HumanFollowUpClaimService,
) {
    private val fixtures = RunRetryTestFixture(cases, timelines, transactions, jdbc, results)

    @Test
    fun `historical replay survives another resolver claiming work without implying current access`() {
        val fixture = exhausted()
        val original = command(fixture)
        val first = service.escalate(original).stored
        claims.claim(original.tenantId, first.followUpWorkItemId, fixture.incoming.value)
        // A history acknowledgement must not consult/re-establish current run supervision.
        val historyOnly =
            object : RunSupervisorAssignmentRepository by assignments {
                override fun findAssigned(
                    tenantId: TenantId,
                    runId: ResolutionRunId,
                    actorId: HumanActorId,
                    at: Instant,
                ): CurrentRunSupervision? = error("replay must not disclose current assigned content")
            }
        val replay =
            RunEscalationService(
                historyOnly,
                commands,
                records,
                policy(),
                runtime,
                transactions,
                Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC),
                true,
                controls = controlGate,
            )
        assertThat(replay.escalate(original).stored).isEqualTo(first)
        send(original).andExpect(status().isOk).andExpect(jsonPath("$.creationStatus").value("OPEN"))
        assertThat(count("human_follow_up_claims", fixture.tenant)).isEqualTo(1)
        assertThat(count("resolution_run_escalation_commands", fixture.tenant)).isEqualTo(1)
    }

    private fun exhausted(): RunSupervisionDatabaseFixture {
        val fixture = fixtures.seed()
        val successor =
            retries
                .retry(fixture.tenant.value, fixture.run.value, 4, fixture.outgoing.value)
                .replacementRun.run.id
        val initial =
            transactions.required {
                assignments.create(
                    fixture.tenant,
                    fixture.initial.assignment.copy(
                        id = UUID.randomUUID(),
                        runId = successor,
                        commandId = UUID.randomUUID(),
                    ),
                )
            }
        fixtures.failRun(fixture.tenant, successor)
        return fixture.copy(run = successor, initial = initial)
    }

    private fun command(fixture: RunSupervisionDatabaseFixture): EscalateSupervisedRunCommand {
        val review = reviews.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        return EscalateSupervisedRunCommand(
            fixture.tenant.value,
            fixture.run.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(review.supervisionRevision),
            1,
            review.attemptNumber,
            review.retryPolicyRevision,
            review.maximumAttempts,
            ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED,
        )
    }

    private fun serviceAt(
        at: Instant = RunRetryTestFixture.NOW,
        enabled: Boolean = true,
        repository: RunEscalationCommandRepository = commands,
        runtimeService: ResolutionRunEscalationService = runtime,
        retryPolicy: ResolutionRetryPolicy = policy(),
    ) = RunEscalationService(
        assignments,
        repository,
        records,
        retryPolicy,
        runtimeService,
        transactions,
        Clock.fixed(at, ZoneOffset.UTC),
        enabled,
        controls = controlGate,
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

    private fun send(
        command: EscalateSupervisedRunCommand,
        content: String = body(command),
    ) = mvc.perform(
        post(base(command) + "/escalation-commands")
            .with(session(command.actorId))
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content),
    )

    private fun assertFailed(fixture: RunSupervisionDatabaseFixture) {
        assertThat(
            records.transitions
                .findState(fixture.tenant, fixture.run)
                ?.state
                ?.name,
        ).isEqualTo("ACTION_FAILED")
        assertThat(count("resolution_run_escalation_commands", fixture.tenant)).isZero()
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isZero()
    }

    @Test
    fun `invalid protocol values are rejected without writing`() {
        val fixture = exhausted()
        val original = command(fixture)
        for (content in listOf(
            body(original).replace("\"expectedRunVersion\":1", "\"expectedRunVersion\":0"),
            body(original).replace("\"maximumAttempts\":2", "\"maximumAttempts\":0"),
            body(original).replace("\"sourceAttemptNumber\":2", "\"sourceAttemptNumber\":1"),
            body(original).replace("RETRY_ATTEMPT_LIMIT_REACHED", "UNKNOWN_REASON"),
            body(original).replace(original.retryPolicyRevision, "x".repeat(201)),
            "{}",
        )) {
            send(original, content).andExpect(status().isBadRequest)
        }
        assertFailed(fixture)
    }

    @Test
    fun `disabled commands and expired replay never borrow earlier authority`() {
        val fixture = exhausted()
        val original = command(fixture)
        val disabled =
            RunEscalationReviewService(
                assignments,
                records,
                policy(),
                transactions,
                Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC),
                false,
            ).review(original.tenantId, original.runId, original.actorId)
        assertThat(disabled.supported).isFalse()
        assertThat(disabled.canEscalate).isFalse()
        assertThatThrownBy { serviceAt(enabled = false).escalate(original) }
            .isInstanceOf(RunEscalationDisabledException::class.java)
        assertFailed(fixture)
        service.escalate(original)
        assertThatThrownBy { serviceAt(at = RunRetryTestFixture.NOW.plusSeconds(3601)).escalate(original) }
            .isInstanceOf(AssignedRunNotFoundException::class.java)
    }

    @Test
    fun `handover and handback fence earlier intent and foreign replay is neutral absence`() {
        val fixture = exhausted()
        val original = command(fixture)
        handovers.handover(
            HandoverRunSupervisorCommand(
                original.tenantId,
                original.runId,
                original.actorId,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            ),
        )
        assertThatThrownBy { service.escalate(original) }.isInstanceOf(AssignedRunNotFoundException::class.java)
        handovers.handover(
            HandoverRunSupervisorCommand(
                original.tenantId,
                original.runId,
                fixture.incoming.value,
                original.actorId,
                UUID.randomUUID(),
                RunSupervisionRevision(1),
            ),
        )
        assertThatThrownBy { service.escalate(original) }.isInstanceOf(RunEscalationConflictException::class.java)
        val current = command(fixture)
        val stored = service.escalate(current).stored
        assertThat(service.escalate(current).stored).isEqualTo(stored)
        send(current.copy(actorId = fixture.incoming.value)).andExpect(status().isNotFound)
        send(current.copy(sourceAttemptNumber = 3)).andExpect(status().isConflict)
        send(current.copy(runId = fixture.initial.assignment.runId.value, commandId = UUID.randomUUID()))
            .andExpect(status().isConflict)
    }

    @Test
    fun `internal escalation is not adopted by a browser command`() {
        val fixture = exhausted()
        val original = command(fixture)
        val previous = runtime.escalate(original.tenantId, original.runId, fixture.incoming.value)
        send(original).andExpect(status().isConflict)
        assertThat(count("resolution_run_escalation_commands", fixture.tenant)).isZero()
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(1)
        assertThat(previous.escalation.storedEvent.event.authorization.actorId).isEqualTo(fixture.incoming)
        assertThat(reviews.review(original.tenantId, original.runId, original.actorId).reason.name)
            .isEqualTo("RUN_NOT_FAILED")
    }

    @Test
    fun `receipt failure rolls back the runtime terminal event projection and human work`() {
        val fixture = exhausted()
        val failing =
            object : RunEscalationCommandRepository by commands {
                override fun create(
                    tenantId: TenantId,
                    command: StoredRunEscalationCommand,
                ): StoredRunEscalationCommand = throw DataIntegrityViolationException("synthetic audit failure")
            }
        assertThatThrownBy { serviceAt(repository = failing).escalate(command(fixture)) }
            .isInstanceOf(DataAccessException::class.java)
        assertFailed(fixture)
    }

    @Test
    fun `runtime policy cannot silently replace the reviewed exhaustion decision`() {
        val fixture = exhausted()
        val changed =
            ResolutionRunEscalationService(
                records,
                ResolutionRetryPolicy.define(
                    ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v2"),
                    2,
                ),
                identities,
                transactions,
                Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC),
            )
        assertThatThrownBy { serviceAt(runtimeService = changed).escalate(command(fixture)) }
            .isInstanceOf(ResolutionRunEscalationReviewChangedException::class.java)
        assertFailed(fixture)
    }

    @Test
    fun `exact concurrent confirmations return the same immutable work receipt`() {
        val fixture = exhausted()
        val original = command(fixture)
        val results = together({ service.escalate(original).stored }, { service.escalate(original).stored })
        assertThat(results[0]).isEqualTo(results[1])
        assertThat(count("resolution_run_escalation_commands", fixture.tenant)).isEqualTo(1)
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(1)
    }

    @Test
    fun `different concurrent commands have one winner and never duplicate follow-up`() {
        val fixture = exhausted()
        val original = command(fixture)
        val failures =
            together(
                { catchThrowable { service.escalate(original) } },
                { catchThrowable { service.escalate(original.copy(commandId = UUID.randomUUID())) } },
            )
        assertThat(failures.count { it == null }).isEqualTo(1)
        assertThat(failures.filterNotNull().single()).isInstanceOf(RunEscalationConflictException::class.java)
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(1)
    }

    @Test
    fun `browser and internal escalation race preserves sole work and real attribution`() {
        val fixture = exhausted()
        val original = command(fixture)
        val failures =
            together(
                { catchThrowable { service.escalate(original) } },
                { catchThrowable { runtime.escalate(original.tenantId, original.runId, fixture.incoming.value) } },
            )
        assertThat(failures[1]).isNull()
        assertThat(failures[0] == null || failures[0] is RunEscalationConflictException).isTrue()
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(1)
        assertThat(count("resolution_run_escalation_commands", fixture.tenant))
            .isEqualTo(if (failures[0] == null) 1 else 0)
    }

    @Test
    fun `handover racing with escalation permits only one decision under the original epoch`() {
        val fixture = exhausted()
        val original = command(fixture)
        val failures =
            together(
                { catchThrowable { service.escalate(original) } },
                {
                    catchThrowable {
                        handovers.handover(
                            HandoverRunSupervisorCommand(
                                original.tenantId,
                                original.runId,
                                original.actorId,
                                fixture.incoming.value,
                                UUID.randomUUID(),
                                RunSupervisionRevision(0),
                            ),
                        )
                    }
                },
            )
        assertThat(failures.count { it == null }).isEqualTo(1)
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(if (failures[0] == null) 1 else 0)
        if (failures[0] == null) {
            assertThat(failures[1]).isInstanceOf(RunSupervisorHandoverUnavailableException::class.java)
        } else {
            assertThat(failures[0]).isInstanceOf(AssignedRunNotFoundException::class.java)
        }
    }

    @Test
    fun `audit is immutable and foreign source coordinates cannot be inserted`() {
        val fixture = exhausted()
        val original = command(fixture)
        val first = service.escalate(original).stored
        for (sql in listOf(
            "UPDATE resolution_run_escalation_commands SET maximum_attempts = 3 WHERE tenant_id = :tenant",
            "DELETE FROM resolution_run_escalation_commands WHERE tenant_id = :tenant",
        )) {
            assertThatThrownBy {
                jdbc
                    .sql(
                        sql,
                    ).param("tenant", fixture.tenant.value)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
        val other = exhausted()
        val otherIntent = command(other)
        val otherResult = runtime.escalate(other.tenant.value, other.run.value, other.outgoing.value)
        val actual = otherResult.escalation.storedEvent.event
        val valid =
            StoredRunEscalationCommand(
                otherIntent,
                other.initial.assignment.assignedAt,
                actual.authorization.authorityEvidenceId.value,
                actual.id.value,
                otherResult.followUp.item.id.value,
                actual.occurredAt,
                otherResult.escalation.storedEvent.recordedAt,
            )
        for (altered in listOf(
            valid.copy(command = otherIntent.copy(actorId = other.incoming.value)),
            valid.copy(authorityEvidenceId = UUID.randomUUID()),
            valid.copy(followUpWorkItemId = first.followUpWorkItemId),
            valid.copy(command = otherIntent.copy(sourceAttemptNumber = 3)),
            valid.copy(command = otherIntent.copy(retryPolicyRevision = "ergon.dev/policy/resolution-retry/v2")),
            valid.copy(supervisedAt = valid.supervisedAt.plusSeconds(1)),
            valid.copy(requestedAt = valid.requestedAt.plusSeconds(1)),
        )) {
            assertThatThrownBy {
                transactions.required {
                    commands.create(
                        other.tenant,
                        altered,
                    )
                }
            }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("fk_run_escalation_command_")
        }
        assertThat(count("resolution_run_escalation_commands", other.tenant)).isZero()
    }

    @Test
    fun `a racing internal retry cannot grant a third attempt or duplicate terminal work`() {
        val fixture = exhausted()
        val original = command(fixture)
        val failures =
            together(
                { catchThrowable { service.escalate(original) } },
                { catchThrowable { retries.retry(original.tenantId, original.runId, 4, original.actorId) } },
            )
        assertThat(failures[0]).isNull()
        assertThat(
            failures[1],
        ).isInstanceOfAny(
            ResolutionRunRetryLimitReachedException::class.java,
            ResolutionRunRetryStateException::class.java,
        )
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(1)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun escalationClock(): Clock = Clock.fixed(RunRetryTestFixture.NOW, ZoneOffset.UTC)
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

    @BeforeEach
    fun upgradeReleasedEscalationAndFollowUpHistory() =
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
                    ?.version == "20261016200000"
            ) {
                val fixture = exhausted()
                val original = runtime.escalate(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                upgraded.migrate()
                upgraded.validate()
                assertThat(runtime.escalate(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).followUp)
                    .isEqualTo(original.followUp)
                assertThat(assignments.findByRun(fixture.tenant, fixture.run)).isEqualTo(fixture.initial)
                assertThat(count("resolution_run_escalation_commands", fixture.tenant)).isZero()
            }
        }

    @Test
    fun `review confirms exhausted failure and exact HTTP replay returns historical unassigned work`() {
        val fixture = exhausted()
        val command = command(fixture)
        val read =
            mvc
                .perform(get(base(command) + "/escalation-review").with(session(command.actorId)))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.canEscalate").value(true))
                .andExpect(jsonPath("$.reason").value("RETRY_ATTEMPT_LIMIT_REACHED"))
                .andExpect(jsonPath("$.maximumAttempts").value(2))
                .andReturn()
                .response.contentAsString
        assertThat(read).doesNotContain("PRIVATE_", "authorityEvidenceId", "workItemId")
        val first =
            send(command)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.creationStatus").value("OPEN"))
                .andExpect(jsonPath("$.followUpQueueKey").value("access-restoration"))
                .andReturn()
                .response.contentAsString
        assertThat(
            send(command)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).isEqualTo(first)
        assertThat(first).doesNotContain("actorId", "authorityEvidenceId", "PRIVATE_", "provider")
        mvc
            .perform(get(base(command) + "/console").with(session(command.actorId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("ESCALATED"))
            .andExpect(jsonPath("$.stateVersion").value(2))
            .andExpect(jsonPath("$.transitions[1].type").value("ESCALATION_REQUESTED"))
        assertThat(
            records.transitions
                .findState(fixture.tenant, fixture.run)
                ?.state
                ?.name,
        ).isEqualTo("ESCALATED")
        assertThat(count("human_follow_up_work_items", fixture.tenant)).isEqualTo(1)
    }

    @Test
    fun `terminal escalation leaves case and execution authority untouched`() {
        val fixture = exhausted()
        service.escalate(command(fixture))
        assertThat(count("human_follow_up_claims", fixture.tenant)).isZero()
        assertThat(count("resolution_run_supervisor_assignments", fixture.tenant)).isEqualTo(2)
        assertThat(count("resolution_runs", fixture.tenant)).isEqualTo(2)
        assertThat(count("capability_invocation_receipts", fixture.tenant)).isEqualTo(2)
        val authorizationTables =
            listOf(
                "resolution_approval_requests",
                "capability_authorization_grants",
                "capability_authorization_consumptions",
            )
        for (table in authorizationTables) {
            assertThat(count(table, fixture.tenant)).isZero()
        }
        assertThat(
            jdbc
                .sql("SELECT status FROM cases WHERE tenant_id = :tenant")
                .param("tenant", fixture.tenant.value)
                .query(String::class.java)
                .single(),
        ).isEqualTo("OPEN")
    }

    @Test
    fun `stale reviewed coordinates and revised exhaustion cannot commit`() {
        val fixture = exhausted()
        val original = command(fixture)
        listOf(
            original.copy(expectedSupervisionRevision = RunSupervisionRevision(1)),
            original.copy(sourceAttemptNumber = 3),
            original.copy(maximumAttempts = 1),
            original.copy(retryPolicyRevision = "ergon.dev/policy/resolution-retry/v2"),
        ).forEach { altered ->
            assertThatThrownBy { service.escalate(altered) }.isInstanceOf(RunEscalationConflictException::class.java)
        }
        assertThatThrownBy {
            serviceAt(
                retryPolicy = policy(3),
            ).escalate(original)
        }.isInstanceOf(RunEscalationConflictException::class.java)
        assertFailed(fixture)
    }

    @Test
    fun `remaining retry budget and nonfailed state never imply escalation permission`() {
        val fixture = fixtures.seed()
        val review = reviews.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        assertThat(review.canEscalate).isFalse()
        assertThat(review.reason.name).isEqualTo("RETRY_BUDGET_AVAILABLE")
        val forged =
            EscalateSupervisedRunCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
                1,
                2,
                review.retryPolicyRevision,
                2,
                ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED,
            )
        send(forged)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:run-escalation-conflict"))
        assertFailed(fixture)
        val successor =
            retries
                .retry(fixture.tenant.value, fixture.run.value, 4, fixture.outgoing.value)
                .replacementRun.run.id
        transactions.required {
            assignments.create(
                fixture.tenant,
                fixture.initial.assignment.copy(
                    id = UUID.randomUUID(),
                    runId = successor,
                    commandId = UUID.randomUUID(),
                ),
            )
        }
        val waiting = reviews.review(fixture.tenant.value, successor.value, fixture.outgoing.value)
        assertThat(waiting.reason.name).isEqualTo("RUN_NOT_FAILED")
        send(forged.copy(runId = successor.value)).andExpect(status().isConflict)
    }

    @Test
    fun `non-supervisors expired authority foreign contexts authentication and CSRF fail closed`() {
        val fixture = exhausted()
        val original = command(fixture)
        for (actor in listOf(fixture.other, fixture.incoming, fixture.expired)) {
            mvc
                .perform(get(base(original) + "/escalation-review").with(session(actor.value)))
                .andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
            send(original.copy(actorId = actor.value)).andExpect(status().isNotFound)
        }
        mvc.perform(get(base(original) + "/escalation-review")).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(base(original) + "/escalation-commands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(original)),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(base(original) + "/escalation-commands")
                    .with(session(original.actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(original)),
            ).andExpect(status().isForbidden)
        send(original.copy(tenantId = UUID.randomUUID())).andExpect(status().isForbidden)
        send(original.copy(runId = UUID.randomUUID())).andExpect(status().isNotFound)
        assertFailed(fixture)
    }
}
