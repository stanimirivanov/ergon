package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.awaitility.Awaitility.await
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ApprovalDecisionAlreadyExistsException
import org.ergon.controlplane.resolution.application.ApprovalDecisionIdentityGenerator
import org.ergon.controlplane.resolution.application.ApprovalDecisionRecords
import org.ergon.controlplane.resolution.application.ApprovalDecisionRepository
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestRepository
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.DecideRunApprovalCommand
import org.ergon.controlplane.resolution.application.HandoverRunSupervisorCommand
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunApprovalAuthorityMissingException
import org.ergon.controlplane.resolution.application.RunApprovalCommandRepository
import org.ergon.controlplane.resolution.application.RunApprovalConflictException
import org.ergon.controlplane.resolution.application.RunApprovalDecisionService
import org.ergon.controlplane.resolution.application.RunApprovalDisabledException
import org.ergon.controlplane.resolution.application.RunApprovalReviewReader
import org.ergon.controlplane.resolution.application.RunApprovalReviewStatus
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.ergon.controlplane.resolution.application.StoredRunApprovalCommand
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.run-supervision.approval.enabled=true",
        "ergon.run-supervision.handover.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class, RunApprovalIntegrationTest.FixedTime::class)
// Real application/database boundaries prove authorization, not mocked controller outcomes.
@Suppress("LongParameterList")
class RunApprovalIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val controlGate: org.ergon.controlplane.resolution.application.RunCommandControlGate,
    @Autowired private val approvals: RunApprovalDecisionService,
    @Autowired private val requests: ApprovalRequestService,
    @Autowired private val console: AssignedRunConsoleService,
    @Autowired private val handovers: RunSupervisorHandoverService,
    @Autowired private val legacy: ApprovalDecisionService,
    @Autowired private val requestRecords: ApprovalRequestRepository,
    @Autowired private val runRecords: ResolutionRunRepository,
    @Autowired private val authorityRecords: HumanAuthorityRepository,
    @Autowired private val decisionRecords: ApprovalDecisionRepository,
    @Autowired private val assignmentRecords: RunSupervisorAssignmentRepository,
    @Autowired private val stateRecords: ResolutionRunTransitionRepository,
    @Autowired private val commandRecords: RunApprovalCommandRepository,
    @Autowired private val reviewReader: RunApprovalReviewReader,
    @Autowired private val identities: ApprovalDecisionIdentityGenerator,
) {
    @Test
    fun `confirmed approval produces identical replay receipts but no grant invocation or state advance`() {
        val fixture = seed()
        assertThat(console.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value).approvalReview?.status)
            .isEqualTo(RunApprovalReviewStatus.NOT_REQUESTED)
        val command = command(fixture)
        mvc
            .perform(get(consolePath(fixture)).with(session(fixture.outgoing)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvalReview.status").value("PENDING"))
            .andExpect(jsonPath("$.approvalReview.canDecide").value(true))
            .andExpect(jsonPath("$.approvalReview.request.authority").value("RESOLVER"))
        val first =
            postDecision(command, fixture.outgoing)
                .andExpect(status().isCreated)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.actorId").doesNotExist())
                .andExpect(jsonPath("$.authorityEvidenceId").doesNotExist())
                .andReturn()
                .response.contentAsString
        val replay =
            postDecision(command, fixture.outgoing)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(replay).isEqualTo(first)
        val detail = console.get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        assertThat(detail.approvalReview?.status).isEqualTo(RunApprovalReviewStatus.DECIDED)
        assertThat(detail.approvalReview?.canDecide).isFalse()
        assertThat(detail.state.state.name).isEqualTo("WAITING_FOR_APPROVAL")
        for (table in listOf("capability_authorization_grants", "capability_invocation_receipts")) {
            assertThat(
                jdbc
                    .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
                    .param("tenant", fixture.tenant.value)
                    .query(Int::class.java)
                    .single(),
            ).isZero()
        }
        assertThatThrownBy {
            transactions.required {
                jdbc
                    .sql("DELETE FROM resolution_run_approval_commands WHERE tenant_id = :tenant")
                    .param("tenant", fixture.tenant.value)
                    .update()
            }
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `requester approval requires the exact case evidence in addition to supervisor resolver authority`() {
        val fixture = seed(ApprovalRequirement.REQUESTER)
        val command = command(fixture)
        assertThat(
            console
                .get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                .approvalReview.canDecide,
        ).isFalse()
        assertThatThrownBy { approvals.decide(command) }.isInstanceOf(RunApprovalAuthorityMissingException::class.java)
        requesterEvidence(fixture, UUID.randomUUID(), NOW.plusSeconds(600))
        assertThatThrownBy { approvals.decide(command) }.isInstanceOf(RunApprovalAuthorityMissingException::class.java)
        val caseId =
            console
                .get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                .start.run.caseId.value
        requesterEvidence(fixture, caseId, NOW)
        assertThatThrownBy { approvals.decide(command) }.isInstanceOf(RunApprovalAuthorityMissingException::class.java)
        requesterEvidence(fixture, caseId, NOW.plusSeconds(600))
        assertThat(
            console
                .get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                .approvalReview.canDecide,
        ).isTrue()
        val rejected = approvals.decide(command.copy(outcome = ApprovalDecisionOutcome.REJECTED))
        assertThat(rejected.stored.decision.outcome).isEqualTo(ApprovalDecisionOutcome.REJECTED)
        assertThat(
            approvalServiceAt(NOW.plusSeconds(901))
                .decide(command.copy(outcome = ApprovalDecisionOutcome.REJECTED))
                .stored,
        ).isEqualTo(rejected.stored)
        assertThat(
            console
                .get(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
                .state.state.name,
        ).isEqualTo("WAITING_FOR_APPROVAL")
    }

    @Test
    fun `CSRF session tenant non-owner and stale epoch gates prevent decisions`() {
        val fixture = seed()
        val command = command(fixture)
        mvc.perform(post(path(command))).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(path(command))
                    .with(session(fixture.outgoing))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
        for (actor in listOf(fixture.other, fixture.expired)) {
            mvc
                .perform(
                    post(
                        path(command),
                    ).with(session(actor))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(command)),
                ).andExpect(status().isNotFound)
        }
        val foreign = seed()
        mvc
            .perform(
                post(
                    path(command),
                ).with(session(foreign.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path(command))
                    .with(session(fixture.outgoing))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command.copy(expectedRevision = RunSupervisionRevision(1)))),
            ).andExpect(status().isConflict)
        assertThat(commandCount(fixture)).isZero()
    }

    @Test
    fun `historical exact replay survives handover but changed or foreign replay cannot expose it`() {
        val fixture = seed()
        val command = command(fixture)
        val first = approvals.decide(command)
        handovers.handover(
            HandoverRunSupervisorCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                fixture.incoming.value,
                UUID.randomUUID(),
                RunSupervisionRevision(0),
            ),
        )
        assertThat(approvals.decide(command).stored).isEqualTo(first.stored)
        assertThat(approvals.decide(command).created).isFalse()
        assertThatThrownBy {
            approvals.decide(command.copy(outcome = ApprovalDecisionOutcome.REJECTED))
        }.isInstanceOf(RunApprovalConflictException::class.java)
        mvc
            .perform(
                post(
                    path(command),
                ).with(session(fixture.incoming))
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(command)),
            ).andExpect(status().isNotFound)
        assertThat(commandCount(fixture)).isEqualTo(1)
    }

    @Test
    fun `competing decisions produce one immutable response and exact concurrent replay shares one receipt`() {
        val fixture = seed()
        val first = command(fixture)
        val competing =
            compete(
                first,
                first.copy(commandId = UUID.randomUUID(), outcome = ApprovalDecisionOutcome.REJECTED),
            )
        assertThat(competing.count { it.isSuccess }).isEqualTo(1)
        assertThat(competing.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(RunApprovalConflictException::class.java)
        val secondFixture = seed()
        val exact = command(secondFixture)
        val receipts = compete(exact, exact).map { it.getOrThrow() }
        assertThat(receipts.count { it.created }).isEqualTo(1)
        assertThat(receipts.map { it.stored }.distinct()).hasSize(1)
    }

    @Test
    fun `expiry at the exclusive boundary and a non-waiting runtime cannot accept a new decision`() {
        val fixture = seed()
        val command = command(fixture)
        val expired =
            transactions.required {
                requests.get(fixture.tenant.value, command.requestId).stored
            }
        // A separate clock tests time-derived expiry without mutating immutable audit rows.
        val expiryService = approvalServiceAt(expired.request.expiresAt)
        assertThatThrownBy { expiryService.decide(command) }.isInstanceOf(RunApprovalConflictException::class.java)
        jdbc
            .sql(
                """
                UPDATE resolution_run_states SET state = 'VERIFYING', version = 1
                WHERE tenant_id = :tenant AND run_id = :run
                """.trimIndent(),
            ).param("tenant", fixture.tenant.value)
            .param("run", fixture.run.value)
            .update()
        assertThatThrownBy { approvals.decide(command) }.isInstanceOf(RunApprovalConflictException::class.java)
        assertThat(commandCount(fixture)).isZero()
    }

    @Test
    fun `disabled commands write nothing and a receipt foreign key failure rolls back the decision`() {
        val fixture = seed()
        val command = command(fixture)
        assertThatThrownBy {
            approvalServiceAt(
                NOW,
                enabled = false,
            ).decide(command)
        }.isInstanceOf(RunApprovalDisabledException::class.java)
        val invalidReceipt =
            object : RunApprovalCommandRepository by commandRecords {
                override fun record(
                    command: DecideRunApprovalCommand,
                    supervision: CurrentRunSupervision,
                    decisionId: ApprovalDecisionId,
                ): StoredRunApprovalCommand =
                    commandRecords.record(
                        command.copy(expectedRevision = RunSupervisionRevision(1)),
                        supervision,
                        decisionId,
                    )
            }
        assertThatThrownBy {
            approvalServiceAt(
                NOW,
                commands = invalidReceipt,
            ).decide(command)
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(commandCount(fixture)).isZero()
        assertThat(
            decisionRecords.findIdByRequest(
                fixture.tenant,
                org.ergon.resolution.domain
                    .ApprovalRequestId(command.requestId),
            ),
        ).isNull()
        assertThat(approvals.decide(command).created).isTrue()
    }

    @Test
    fun `legacy decision waits at the run fence without holding a request lock needed by browser approval`() {
        val fixture = seed()
        val command = command(fixture)
        val legacyBackend = AtomicInteger()
        Executors.newSingleThreadExecutor().use { executor ->
            val waiting =
                transactions.required {
                    check(assignmentRecords.lockRun(fixture.tenant, fixture.run))
                    val legacyAttempt =
                        executor.submit(
                            Callable {
                                catchThrowable {
                                    transactions.required {
                                        val pid = jdbc.sql("SELECT pg_backend_pid()").query(Int::class.java).single()
                                        legacyBackend.set(pid)
                                        legacy.decide(
                                            fixture.tenant.value,
                                            command.requestId,
                                            fixture.other.value,
                                            ApprovalDecisionOutcome.REJECTED,
                                        )
                                    }
                                }
                            },
                        )
                    // Observe the real PostgreSQL lock wait, not thread scheduling or a sleep.
                    await().atMost(20, TimeUnit.SECONDS).until {
                        legacyBackend.get() != 0 &&
                            jdbc
                                .sql("SELECT CARDINALITY(pg_blocking_pids(:pid)) > 0")
                                .param("pid", legacyBackend.get())
                                .query(Boolean::class.java)
                                .single()
                    }
                    // The legacy waiter must not hold the child lock: browser approval
                    // can finish under this run fence and its FK checks remain compatible.
                    assertThat(approvals.decide(command).created).isTrue()
                    legacyAttempt
                }
            assertThat(waiting.get(20, TimeUnit.SECONDS))
                .isInstanceOf(ApprovalDecisionAlreadyExistsException::class.java)
        }
        assertThat(commandCount(fixture)).isEqualTo(1)
    }

    private fun approvalServiceAt(
        at: Instant,
        enabled: Boolean = true,
        commands: RunApprovalCommandRepository = commandRecords,
        clock: Clock = Clock.fixed(at, ZoneOffset.UTC),
    ): RunApprovalDecisionService =
        RunApprovalDecisionService(
            ApprovalDecisionRecords(requestRecords, runRecords, authorityRecords, decisionRecords),
            assignmentRecords,
            stateRecords,
            commands,
            reviewReader,
            identities,
            transactions,
            clock,
            enabled,
            controls = controlGate,
        )

    private fun requesterEvidence(
        fixture: RunSupervisionDatabaseFixture,
        caseId: UUID,
        expiry: Instant,
    ) = transactions.required {
        // Only earlier case-source FKs are bypassed; authority shape checks and every decision FK stay enabled.
        jdbc.sql("SET LOCAL session_replication_role = replica").update()
        try {
            jdbc
                .sql(
                    """INSERT INTO approval_authority_evidence (tenant_id, evidence_id, actor_id, authority, case_id,
                source_provider, source_reference, attested_at, expires_at)
                VALUES (:tenant, :id, :actor, 'REQUESTER', :caseId, 'workforce-sso', 'requester', :at, :expiry)""",
                ).param("tenant", fixture.tenant.value)
                .param("id", UUID.randomUUID())
                .param("actor", fixture.outgoing.value)
                .param(
                    "caseId",
                    caseId,
                ).param("at", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("expiry", expiry.atOffset(ZoneOffset.UTC))
                .update()
        } finally {
            jdbc.sql("SET LOCAL session_replication_role = origin").update()
        }
    }

    private fun seed(authority: ApprovalRequirement = ApprovalRequirement.RESOLVER) =
        transactions.required {
            RunSupervisionDatabaseFixture.seed(jdbc, NOW, authority)
        }

    private fun command(fixture: RunSupervisionDatabaseFixture): DecideRunApprovalCommand {
        val request = requests.request(fixture.tenant.value, fixture.run.value)
        return DecideRunApprovalCommand(
            fixture.tenant.value,
            fixture.run.value,
            request.stored.request.id.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            ApprovalDecisionOutcome.APPROVED,
        )
    }

    private fun commandCount(fixture: RunSupervisionDatabaseFixture) =
        jdbc
            .sql("SELECT COUNT(*) FROM resolution_run_approval_commands WHERE tenant_id = :tenant")
            .param("tenant", fixture.tenant.value)
            .query(Int::class.java)
            .single()

    private fun path(command: DecideRunApprovalCommand) =
        "/bff/v1/tenants/${command.tenantId}/resolution-runs/${command.runId}" +
            "/approval-requests/${command.requestId}/decisions"

    private fun consolePath(fixture: RunSupervisionDatabaseFixture) =
        "/bff/v1/tenants/${fixture.tenant.value}/resolution-runs/${fixture.run.value}/console"

    private fun body(command: DecideRunApprovalCommand) =
        """
        {"commandId":"${command.commandId}",
         "expectedSupervisionRevision":${command.expectedRevision.value},"outcome":"${command.outcome}"}
        """.trimIndent()

    private fun postDecision(
        command: DecideRunApprovalCommand,
        actor: HumanActorId,
    ) = mvc.perform(
        post(path(command))
            .with(session(actor))
            .with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(command)),
    )

    @Test
    fun `a sub-microsecond pre-expiry decision is persisted strictly inside the request interval`() {
        val fixture = seed()
        val command = command(fixture)
        val expiry =
            requests
                .get(fixture.tenant.value, command.requestId)
                .stored.request.expiresAt
        val first = approvalServiceAt(expiry.minusNanos(1)).decide(command).stored
        assertThat(first.decision.decidedAt).isBefore(expiry)
        assertThat(first.decision.decidedAt.nano % 1000).isZero()
        assertThat(approvalServiceAt(expiry.plusSeconds(1)).decide(command).stored).isEqualTo(first)
    }

    @Test
    fun `lock waits cannot preserve an expired request or resolver authority from the initial access check`() {
        val fixture = seed()
        val command = command(fixture)
        val expiry =
            requests
                .get(fixture.tenant.value, command.requestId)
                .stored.request.expiresAt
        for (finalAt in listOf(expiry, NOW.plusSeconds(3601))) {
            val steppedClock = Mockito.mock(Clock::class.java)
            Mockito.`when`(steppedClock.instant()).thenReturn(NOW, finalAt)
            val expected =
                if (finalAt == expiry) {
                    RunApprovalConflictException::class.java
                } else {
                    AssignedRunNotFoundException::class.java
                }
            assertThatThrownBy { approvalServiceAt(NOW, clock = steppedClock).decide(command) }.isInstanceOf(expected)
        }
        assertThat(commandCount(fixture)).isZero()
    }

    private fun session(actor: HumanActorId) =
        oidcLogin().idToken {
            it.issuer("https://identity.example.test")
            it.subject("resolver-${actor.value}")
        }

    private fun compete(
        first: DecideRunApprovalCommand,
        second: DecideRunApprovalCommand,
    ) = Executors.newFixedThreadPool(2).use { executor ->
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val tasks =
            listOf(first, second).map { command ->
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        runCatching { approvals.decide(command) }
                    },
                )
            }
        try {
            check(ready.await(10, TimeUnit.SECONDS))
        } finally {
            start.countDown()
        }
        tasks.map { it.get(20, TimeUnit.SECONDS) }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun approvalTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-14T12:00:00.123456123Z")

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
