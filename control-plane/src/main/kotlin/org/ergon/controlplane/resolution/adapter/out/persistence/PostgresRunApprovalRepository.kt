package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.DecideRunApprovalCommand
import org.ergon.controlplane.resolution.application.LatestRunApproval
import org.ergon.controlplane.resolution.application.RunApprovalCommandRepository
import org.ergon.controlplane.resolution.application.RunApprovalDecisionSummary
import org.ergon.controlplane.resolution.application.RunApprovalReviewReader
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredApprovalRequest
import org.ergon.controlplane.resolution.application.StoredRunApprovalCommand
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Tenant-scoped approval review and immutable browser command replay; caller owns transaction/lock lifetime. */
@Repository
class PostgresRunApprovalRepository(
    private val jdbc: JdbcClient,
) : RunApprovalReviewReader,
    RunApprovalCommandRepository {
    override fun findLatest(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): LatestRunApproval? =
        jdbc
            .sql(
                """
                SELECT r.approval_request_id, r.run_id, r.step_id, r.required_authority,
                    r.requested_at, r.expires_at, r.recorded_at AS request_recorded_at,
                    d.approval_decision_id, d.outcome, d.decided_at, d.recorded_at AS decision_recorded_at
                FROM resolution_approval_requests r
                LEFT JOIN resolution_approval_decisions d
                    ON d.tenant_id = r.tenant_id AND d.approval_request_id = r.approval_request_id
                WHERE r.tenant_id = :tenant AND r.run_id = :run
                ORDER BY r.recorded_at DESC, r.approval_request_id DESC
                LIMIT 1
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("run", runId.value)
            .query(DataClassRowMapper(ReviewRow::class.java))
            .optional()
            .getOrNull()
            ?.toLatest()

    override fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-approval:${tenant.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun findByCommandId(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunApprovalCommand? =
        jdbc
            .sql(
                """
                SELECT c.run_id, c.actor_id, c.supervision_revision, d.approval_request_id,
                    d.approval_decision_id, d.outcome, d.decided_at, d.recorded_at
                FROM resolution_run_approval_commands c
                JOIN resolution_approval_decisions d
                    ON d.tenant_id = c.tenant_id AND d.approval_decision_id = c.approval_decision_id
                WHERE c.tenant_id = :tenant AND c.command_id = :command
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("command", commandId)
            .query(DataClassRowMapper(CommandRow::class.java))
            .optional()
            .getOrNull()
            ?.let { row ->
                try {
                    val outcome = ApprovalDecisionOutcome.valueOf(row.outcome)
                    StoredRunApprovalCommand(
                        DecideRunApprovalCommand(
                            tenant.value,
                            row.runId,
                            row.approvalRequestId,
                            row.actorId,
                            commandId,
                            RunSupervisionRevision(row.supervisionRevision),
                            outcome,
                        ),
                        RunApprovalDecisionSummary(
                            row.approvalDecisionId,
                            outcome,
                            row.decidedAt.toInstant(),
                            row.recordedAt.toInstant(),
                        ),
                    )
                } catch (exception: IllegalArgumentException) {
                    throw IllegalStateException("stored approval command is invalid", exception)
                }
            }

    override fun record(
        command: DecideRunApprovalCommand,
        supervision: CurrentRunSupervision,
        decisionId: ApprovalDecisionId,
    ): StoredRunApprovalCommand {
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_approval_commands (
                    tenant_id, command_id, approval_decision_id, run_id, actor_id, supervision_revision, supervised_at
                ) VALUES (:tenant, :command, :decision, :run, :actor, :revision, :supervisedAt)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("command", command.commandId)
            .param("decision", decisionId.value)
            .param("run", command.runId)
            .param("actor", command.actorId)
            .param("revision", command.expectedRevision.value)
            .param("supervisedAt", supervision.supervisedAt.atOffset(ZoneOffset.UTC))
            .update()
        return checkNotNull(findByCommandId(TenantId(command.tenantId), command.commandId))
    }
}

private data class CommandRow(
    val runId: UUID,
    val actorId: UUID,
    val supervisionRevision: Long,
    val approvalRequestId: UUID,
    val approvalDecisionId: UUID,
    val outcome: String,
    val decidedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)

private data class ReviewRow(
    val approvalRequestId: UUID,
    val runId: UUID,
    val stepId: String,
    val requiredAuthority: String,
    val requestedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val requestRecordedAt: OffsetDateTime,
    val approvalDecisionId: UUID?,
    val outcome: String?,
    val decidedAt: OffsetDateTime?,
    val decisionRecordedAt: OffsetDateTime?,
) {
    fun toLatest(): LatestRunApproval =
        try {
            LatestRunApproval(
                StoredApprovalRequest(
                    ApprovalRequest(
                        ApprovalRequestId(approvalRequestId),
                        ResolutionRunId(runId),
                        ResolutionStepId.of(stepId),
                        ApprovalAuthority.valueOf(requiredAuthority),
                        requestedAt.toInstant(),
                        expiresAt.toInstant(),
                    ),
                    requestRecordedAt.toInstant(),
                ),
                approvalDecisionId?.let {
                    RunApprovalDecisionSummary(
                        it,
                        ApprovalDecisionOutcome.valueOf(checkNotNull(outcome)),
                        checkNotNull(decidedAt).toInstant(),
                        checkNotNull(decisionRecordedAt).toInstant(),
                    )
                },
            )
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("stored run approval is invalid", exception)
        }
}
