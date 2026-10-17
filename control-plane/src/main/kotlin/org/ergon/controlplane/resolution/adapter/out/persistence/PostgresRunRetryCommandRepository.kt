package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.resolution.application.RetrySupervisedRunCommand
import org.ergon.controlplane.resolution.application.ReviewedRunRetryPlan
import org.ergon.controlplane.resolution.application.RunRetryCommandRepository
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredRunRetryCommand
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Exact retry audit and replay; runtime transitions and assignment projection remain with their existing writers. */
@Repository
class PostgresRunRetryCommandRepository(
    private val jdbc: JdbcClient,
) : RunRetryCommandRepository {
    override fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-retry-command:${tenantId.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun find(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunRetryCommand? =
        jdbc
            .sql(
                """
                SELECT tenant_id, command_id, run_id, actor_id, supervision_revision, supervised_at,
                    expected_run_version, case_stream_version, contract_key, contract_revision,
                    policy_revision, step_id, capability, effective_risk, required_approval,
                    retry_policy_revision, maximum_attempts, source_attempt_number, successor_run_id,
                    assignment_id, authority_evidence_id, retry_event_id, started_at, recorded_at
                FROM resolution_run_retry_commands
                WHERE tenant_id = :tenant AND command_id = :command
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("command", commandId)
            .query(DataClassRowMapper(RunRetryCommandRow::class.java))
            .optional()
            .getOrNull()
            ?.toStored()

    override fun create(
        tenantId: TenantId,
        command: StoredRunRetryCommand,
    ): StoredRunRetryCommand {
        val intent = command.command
        val plan = intent.plan
        val instants =
            jdbc
                .sql(
                    """
                    INSERT INTO resolution_run_retry_commands (
                        tenant_id, command_id, run_id, actor_id, supervision_revision, supervised_at, expected_run_version,
                        case_stream_version, contract_key, contract_revision, policy_revision, step_id, capability,
                        effective_risk, required_approval, retry_policy_revision, maximum_attempts, source_attempt_number,
                        successor_run_id, assignment_id, authority_evidence_id, retry_event_id, started_at
                    ) VALUES (
                        :tenant, :command, :run, :actor, :revision, :supervisedAt, :runVersion,
                        :caseVersion, :contract, :contractRevision, :policy, :step, :capability,
                        :risk, :approval, :retryPolicy, :ceiling, :sourceAttempt,
                        :successor, :assignment, :authority, :event, :startedAt
                    ) RETURNING started_at, recorded_at
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to tenantId.value,
                        "command" to intent.commandId,
                        "run" to intent.runId,
                        "actor" to intent.actorId,
                        "revision" to intent.expectedSupervisionRevision.value,
                        "supervisedAt" to command.supervisedAt.atOffset(ZoneOffset.UTC),
                        "runVersion" to intent.expectedRunVersion,
                        "caseVersion" to plan.caseStreamVersion,
                        "contract" to plan.contractKey,
                        "contractRevision" to plan.contractRevision,
                        "policy" to plan.policyRevision,
                        "step" to plan.stepId,
                        "capability" to plan.capability,
                        "risk" to plan.effectiveRisk.name,
                        "approval" to plan.requiredApproval.name,
                        "retryPolicy" to intent.retryPolicyRevision,
                        "ceiling" to intent.maximumAttempts,
                        "sourceAttempt" to command.sourceAttemptNumber,
                        "successor" to command.successorRunId,
                        "assignment" to command.assignmentId,
                        "authority" to command.authorityEvidenceId,
                        "event" to command.retryEventId,
                        "startedAt" to command.startedAt.atOffset(ZoneOffset.UTC),
                    ),
                ).query(DataClassRowMapper(RetryCommandInstants::class.java))
                .single()
        return command.copy(startedAt = instants.startedAt.toInstant(), recordedAt = instants.recordedAt.toInstant())
    }
}

private data class RetryCommandInstants(
    val startedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)

private data class RunRetryCommandRow(
    val tenantId: UUID,
    val commandId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val supervisionRevision: Long,
    val supervisedAt: OffsetDateTime,
    val expectedRunVersion: Long,
    val caseStreamVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val effectiveRisk: String,
    val requiredApproval: String,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val sourceAttemptNumber: Int,
    val successorRunId: UUID,
    val assignmentId: UUID,
    val authorityEvidenceId: UUID,
    val retryEventId: UUID,
    val startedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toStored(): StoredRunRetryCommand =
        StoredRunRetryCommand(
            RetrySupervisedRunCommand(
                tenantId,
                runId,
                actorId,
                commandId,
                RunSupervisionRevision(supervisionRevision),
                expectedRunVersion,
                retryPolicyRevision,
                maximumAttempts,
                ReviewedRunRetryPlan(
                    caseStreamVersion,
                    contractKey,
                    contractRevision,
                    policyRevision,
                    stepId,
                    capability,
                    StepRisk.valueOf(effectiveRisk),
                    ApprovalRequirement.valueOf(requiredApproval),
                ),
            ),
            successorRunId,
            assignmentId,
            authorityEvidenceId,
            retryEventId,
            sourceAttemptNumber,
            supervisedAt.toInstant(),
            startedAt.toInstant(),
            recordedAt.toInstant(),
        )
}
