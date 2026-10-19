package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.CapabilityInvocationService
import org.ergon.controlplane.resolution.application.DecideRunApprovalCommand
import org.ergon.controlplane.resolution.application.EscalateSupervisedRunCommand
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.ergon.controlplane.resolution.application.ResumeRunExecutionCommand
import org.ergon.controlplane.resolution.application.RetrySupervisedRunCommand
import org.ergon.controlplane.resolution.application.RunApprovalDecisionService
import org.ergon.controlplane.resolution.application.RunCommandControl
import org.ergon.controlplane.resolution.application.RunControlAction
import org.ergon.controlplane.resolution.application.RunControlCommand
import org.ergon.controlplane.resolution.application.RunControlConflictException
import org.ergon.controlplane.resolution.application.RunControlRequiredException
import org.ergon.controlplane.resolution.application.RunControlService
import org.ergon.controlplane.resolution.application.RunControlStatus
import org.ergon.controlplane.resolution.application.RunEscalationReviewService
import org.ergon.controlplane.resolution.application.RunEscalationService
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionService
import org.ergon.controlplane.resolution.application.RunProofFinalizationService
import org.ergon.controlplane.resolution.application.RunRetryReviewService
import org.ergon.controlplane.resolution.application.RunRetryService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true", "ergon.run-supervision.control.enabled=true",
        "ergon.run-supervision.approval.enabled=true", "ergon.run-supervision.execution.enabled=true",
        "ergon.run-supervision.proof-finalization.enabled=true", "ergon.run-supervision.retry.enabled=true",
        "ergon.run-supervision.escalation.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD) // Keep the context-owned clock independent of test-method scheduling.
@Import(BrowserSessionTestClientConfiguration::class, RunControlIntegrationTest.TimeConfiguration::class)
@Suppress("LongParameterList") // Exercise each real command owner; do not replace admission or rollback with mocks.
class RunControlCommandIntegrationTest(
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionRunner,
    @Autowired private val controls: RunControlService,
    @Autowired private val clock: MutableRunControlClock,
    @Autowired private val requests: ApprovalRequestService,
    @Autowired private val approvals: RunApprovalDecisionService,
    @Autowired private val executions: RunExecutionAdmissionService,
    @Autowired private val invocations: CapabilityInvocationService,
    @Autowired private val proofs: RunProofFinalizationService,
    @Autowired private val retries: RunRetryService,
    @Autowired private val retryReviews: RunRetryReviewService,
    @Autowired private val escalations: RunEscalationService,
    @Autowired private val escalationReviews: RunEscalationReviewService,
    @Autowired private val runtimeRetries: ResolutionRunRetryService,
    @Autowired private val assignments: RunSupervisorAssignmentRepository,
    @Autowired cases: CaseCommandService,
    @Autowired timelines: CaseTimelineRepository,
    @Autowired private val results: ResolutionRunCapabilityResultService,
    @Autowired console: AssignedRunConsoleService,
) {
    private val retryFixtures = RunRetryTestFixture(cases, timelines, transactions, jdbc, results)
    private val proofFixtures = RunProofTestFixture(cases, timelines, transactions, jdbc, results, console)

    @BeforeEach
    fun resetTime() {
        clock.set(RunRetryTestFixture.NOW)
    }

    @Test
    fun `approval execution and each recovery attempt fence their own atomic admission not historical replay`() {
        val fixture = transactions.required { RunSupervisionDatabaseFixture.seed(jdbc, RunRetryTestFixture.NOW) }
        val decision = pendingApproval(fixture)
        assertThatThrownBy { approvals.decide(decision) }.isInstanceOf(RunControlRequiredException::class.java)
        assertThat(count("resolution_approval_decisions", fixture)).isZero()
        val context = acquire(fixture)
        val approved = approvals.decide(decision, context).stored
        val command =
            ExecuteRunCommand(
                decision.tenantId,
                decision.runId,
                decision.actorId,
                UUID.randomUUID(),
                decision.expectedRevision,
                0,
                decision.requestId,
                approved.decision.decisionId,
            )
        assertThatThrownBy { executions.admit(command) }.isInstanceOf(RunControlRequiredException::class.java)
        assertThat(count("capability_authorization_grants", fixture)).isZero()
        assertThat(count("capability_authorization_consumptions", fixture)).isZero()
        val admitted = executions.admit(command, context)
        assertThat(admitted.dispatch).isTrue()
        assertThat(count("resolution_run_control_admissions", fixture)).isEqualTo(2)
        clock.set(RunRetryTestFixture.NOW.plusSeconds(120))
        val recovery =
            ResumeRunExecutionCommand(
                command.tenantId,
                command.runId,
                command.actorId,
                command.commandId,
                UUID.randomUUID(),
                command.expectedSupervisionRevision,
                0,
            )
        assertThatThrownBy { executions.resume(recovery, context) }
            .isInstanceOf(RunControlConflictException::class.java)
        assertThat(count("resolution_run_execution_attempts", fixture)).isEqualTo(1)
        assertThat(executions.admit(command, context).dispatch).isFalse()
        assertThat(executions.resume(recovery.copy(attemptId = command.commandId), context).dispatch).isFalse()
        val renewedControl = acquire(fixture)
        assertThat(executions.resume(recovery.copy(attemptId = UUID.randomUUID()), renewedControl).dispatch).isTrue()
        assertThat(approvals.decide(decision, context).stored).isEqualTo(approved)
        assertThat(count("capability_authorization_grants", fixture)).isEqualTo(1)
        assertThat(count("capability_authorization_consumptions", fixture)).isEqualTo(1)
        assertThat(count("resolution_run_control_admissions", fixture)).isEqualTo(3)
        // Runtime progression is already admitted: a later lease lapse is not provider cancellation.
        clock.set(RunRetryTestFixture.NOW.plusSeconds(240))
        invocations.invoke(command.tenantId, admitted.stored.consumptionId.value)
        results.record(command.tenantId, command.runId)
        assertThat(state(fixture)).isEqualTo("VERIFYING")
        assertThat(count("capability_invocation_receipts", fixture)).isEqualTo(1)
    }

    @Test
    fun `proof fence rolls back provisional closure and accepts exactly once with historical recovery`() {
        clock.set(RunProofTestFixture.NOW)
        val fixture = proofFixtures.seed()
        val command = proofFixtures.command(fixture)
        assertThatThrownBy { proofs.finalizeProof(command) }.isInstanceOf(RunControlRequiredException::class.java)
        assertThat(count("resolution_run_proof_commands", fixture)).isZero()
        assertThat(state(fixture)).isEqualTo("VERIFYING")
        val context = acquire(fixture)
        val first = proofs.finalizeProof(command, context).stored
        clock.set(RunProofTestFixture.NOW.plusSeconds(120))
        assertThat(proofs.finalizeProof(command, context).stored).isEqualTo(first)
        assertThat(state(fixture)).isEqualTo("VERIFIED_RESOLVED")
        assertThat(count("resolution_run_proof_commands", fixture)).isEqualTo(1)
        assertThat(count("resolution_run_control_admissions", fixture)).isEqualTo(1)
    }

    @Test
    fun `retry rolls back successor and assignment without control and never inherits a predecessor lease`() {
        val fixture = retryFixtures.seed()
        val review = retryReviews.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        val command =
            RetrySupervisedRunCommand(
                fixture.tenant.value,
                fixture.run.value,
                fixture.outgoing.value,
                UUID.randomUUID(),
                RunSupervisionRevision(review.supervisionRevision),
                1,
                review.retryPolicyRevision,
                review.maximumAttempts,
                checkNotNull(review.plan),
            )
        assertThatThrownBy { retries.retry(command) }.isInstanceOf(RunControlRequiredException::class.java)
        assertThat(count("resolution_runs", fixture)).isEqualTo(1)
        assertThat(count("resolution_run_supervisor_assignments", fixture)).isEqualTo(1)
        assertThat(state(fixture)).isEqualTo("ACTION_FAILED")
        val context = acquire(fixture)
        val first = retries.retry(command, context).stored
        assertThat(controls.status(command.tenantId, first.successorRunId, command.actorId, context.holderId).status)
            .isEqualTo(RunControlStatus.AVAILABLE)
        clock.set(RunRetryTestFixture.NOW.plusSeconds(120))
        assertThat(retries.retry(command, context).stored).isEqualTo(first)
        assertThat(count("resolution_runs", fixture)).isEqualTo(2)
        assertThat(count("resolution_run_control_events", fixture)).isEqualTo(1)
    }

    @Test
    fun `exhaustion rolls back terminal follow-up without control then records one controlled escalation`() {
        val predecessor = retryFixtures.seed()
        val successor =
            runtimeRetries
                .retry(
                    predecessor.tenant.value,
                    predecessor.run.value,
                    4,
                    predecessor.outgoing.value,
                ).replacementRun.run.id
        val initial =
            transactions.required {
                assignments.create(
                    predecessor.tenant,
                    predecessor.initial.assignment.copy(
                        id = UUID.randomUUID(),
                        runId = successor,
                        commandId = UUID.randomUUID(),
                    ),
                )
            }
        retryFixtures.failRun(predecessor.tenant, successor)
        val fixture = predecessor.copy(run = successor, initial = initial)
        val review = escalationReviews.review(fixture.tenant.value, fixture.run.value, fixture.outgoing.value)
        val command =
            EscalateSupervisedRunCommand(
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
        assertThatThrownBy { escalations.escalate(command) }.isInstanceOf(RunControlRequiredException::class.java)
        assertThat(count("human_follow_up_work_items", fixture)).isZero()
        assertThat(state(fixture)).isEqualTo("ACTION_FAILED")
        val context = acquire(fixture)
        val first = escalations.escalate(command, context).stored
        clock.set(RunRetryTestFixture.NOW.plusSeconds(120))
        assertThat(escalations.escalate(command, context).stored).isEqualTo(first)
        assertThat(count("human_follow_up_work_items", fixture)).isEqualTo(1)
        assertThat(state(fixture)).isEqualTo("ESCALATED")
    }

    private fun pendingApproval(fixture: RunSupervisionDatabaseFixture): DecideRunApprovalCommand {
        jdbc
            .sql(
                """
                INSERT INTO tenant_capability_routes (tenant_id, capability, connector)
                VALUES (:tenant, 'identity.account.unlock', 'identity-stub')
                """.trimIndent(),
            ).param("tenant", fixture.tenant.value)
            .update()
        val request = requests.request(fixture.tenant.value, fixture.run.value).stored.request
        return DecideRunApprovalCommand(
            fixture.tenant.value,
            fixture.run.value,
            request.id.value,
            fixture.outgoing.value,
            UUID.randomUUID(),
            RunSupervisionRevision(0),
            ApprovalDecisionOutcome.APPROVED,
        )
    }

    private fun acquire(fixture: RunSupervisionDatabaseFixture): RunCommandControl {
        val holder = UUID.randomUUID()
        val lease =
            controls.change(
                RunControlCommand(
                    fixture.tenant.value,
                    fixture.run.value,
                    fixture.outgoing.value,
                    UUID.randomUUID(),
                    RunSupervisionRevision(0),
                    RunControlAction.ACQUIRE,
                    null,
                ),
                holder,
            )
        return RunCommandControl(lease.fence, holder)
    }

    private fun count(
        table: String,
        fixture: RunSupervisionDatabaseFixture,
    ) = jdbc
        .sql("SELECT COUNT(*) FROM $table WHERE tenant_id = :tenant")
        .param("tenant", fixture.tenant.value)
        .query(Int::class.java)
        .single()

    private fun state(fixture: RunSupervisionDatabaseFixture) =
        jdbc
            .sql("SELECT state FROM resolution_run_states WHERE tenant_id = :tenant AND run_id = :run")
            .param("tenant", fixture.tenant.value)
            .param("run", fixture.run.value)
            .query(String::class.java)
            .single()

    companion object {
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
