package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.ResumeRunExecutionCommand
import org.ergon.controlplane.resolution.application.RunExecutionCommandRepository
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredCapabilityAuthorizationConsumption
import org.ergon.controlplane.resolution.application.StoredRunExecutionAttempt
import org.ergon.controlplane.resolution.application.StoredRunExecutionCommand
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Append-only tenant-scoped admission/attempt audit; caller owns transaction and lock ordering. */
@Repository
class PostgresRunExecutionCommandRepository(
    private val jdbc: JdbcClient,
) : RunExecutionCommandRepository {
    override fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    ) = lock("command", tenant, commandId)

    override fun lockAttempt(
        tenant: TenantId,
        attemptId: UUID,
    ) = lock("attempt", tenant, attemptId)

    private fun lock(
        kind: String,
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-execution-$kind:${tenant.value}:$id")
            .query { _, _ -> Unit }
            .single()
    }

    override fun findCommand(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunExecutionCommand? = find(tenant, "command_id", commandId)

    override fun findByRun(
        tenant: TenantId,
        run: ResolutionRunId,
    ): StoredRunExecutionCommand? = find(tenant, "run_id", run.value)

    override fun hasPriorAuthorization(
        tenant: TenantId,
        run: ResolutionRunId,
    ): Boolean =
        jdbc
            .sql(
                """SELECT EXISTS (SELECT 1 FROM capability_authorization_grants
            WHERE tenant_id = :tenant AND run_id = :run)""",
            ).param("tenant", tenant.value)
            .param("run", run.value)
            .query(Boolean::class.java)
            .single()

    private fun find(
        tenant: TenantId,
        column: String,
        id: UUID,
    ): StoredRunExecutionCommand? =
        jdbc
            .sql(
                """
                SELECT command_id, run_id, actor_id, supervision_revision, expected_run_version,
                    approval_request_id, approval_decision_id, authorization_grant_id,
                    authorization_consumption_id, recorded_at
                FROM resolution_run_execution_commands
                WHERE tenant_id = :tenant AND $column = :id
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", id)
            .query(DataClassRowMapper(ExecutionCommandRow::class.java))
            .optional()
            .getOrNull()
            ?.let {
                StoredRunExecutionCommand(
                    ExecuteRunCommand(
                        tenant.value,
                        it.runId,
                        it.actorId,
                        it.commandId,
                        RunSupervisionRevision(it.supervisionRevision),
                        it.expectedRunVersion,
                        it.approvalRequestId,
                        it.approvalDecisionId,
                    ),
                    CapabilityAuthorizationGrantId(it.authorizationGrantId),
                    CapabilityAuthorizationConsumptionId(it.authorizationConsumptionId),
                    it.recordedAt.toInstant(),
                )
            }

    override fun findAttempt(
        tenant: TenantId,
        attemptId: UUID,
    ): StoredRunExecutionAttempt? =
        jdbc
            .sql(
                """
                SELECT command_id, run_id, actor_id, supervision_revision,
                    expected_run_version, admitted_at, recorded_at
                FROM resolution_run_execution_attempts
                WHERE tenant_id = :tenant AND attempt_id = :attempt
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("attempt", attemptId)
            .query(DataClassRowMapper(ExecutionAttemptRow::class.java))
            .optional()
            .getOrNull()
            ?.let {
                StoredRunExecutionAttempt(
                    ResumeRunExecutionCommand(
                        tenant.value,
                        it.runId,
                        it.actorId,
                        it.commandId,
                        attemptId,
                        RunSupervisionRevision(it.supervisionRevision),
                        it.expectedRunVersion,
                    ),
                    it.admittedAt.toInstant(),
                    it.recordedAt.toInstant(),
                )
            }

    override fun recordCommand(
        command: ExecuteRunCommand,
        consumption: StoredCapabilityAuthorizationConsumption,
        supervision: CurrentRunSupervision,
    ): StoredRunExecutionCommand {
        val reservation = consumption.consumption
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_execution_commands (
                    tenant_id, command_id, run_id, actor_id, supervision_revision, supervised_at, expected_run_version,
                    approval_request_id, approval_decision_id, authorization_grant_id, authorization_consumption_id
                ) VALUES (:tenant, :command, :run, :actor, :revision, :supervised, :version,
                    :request, :decision, :grant, :consumption)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("command", command.commandId)
            .param("run", command.runId)
            .param("actor", command.actorId)
            .param("revision", command.expectedSupervisionRevision.value)
            .param("supervised", supervision.supervisedAt.atOffset(ZoneOffset.UTC))
            .param("version", command.expectedRunVersion)
            .param("request", command.requestId)
            .param("decision", command.decisionId)
            .param("grant", reservation.authorizationGrantId.value)
            .param("consumption", reservation.id.value)
            .update()
        return checkNotNull(findCommand(TenantId(command.tenantId), command.commandId))
    }

    override fun recordAttempt(
        command: ResumeRunExecutionCommand,
        supervision: CurrentRunSupervision,
        evidence: ApprovalAuthorityEvidenceId,
        admittedAt: Instant,
    ): StoredRunExecutionAttempt {
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_execution_attempts (
                    tenant_id, attempt_id, command_id, run_id, actor_id, supervision_revision, supervised_at,
                    expected_run_version, authority_evidence_id, admitted_at
                ) VALUES (:tenant, :attempt, :command, :run, :actor, :revision, :supervised, :version, :evidence, :at)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("attempt", command.attemptId)
            .param("command", command.commandId)
            .param("run", command.runId)
            .param("actor", command.actorId)
            .param("revision", command.expectedSupervisionRevision.value)
            .param("supervised", supervision.supervisedAt.atOffset(ZoneOffset.UTC))
            .param("version", command.expectedRunVersion)
            .param("evidence", evidence.value)
            .param("at", admittedAt.atOffset(ZoneOffset.UTC))
            .update()
        return checkNotNull(findAttempt(TenantId(command.tenantId), command.attemptId))
    }
}

private data class ExecutionCommandRow(
    val commandId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val approvalRequestId: UUID,
    val approvalDecisionId: UUID,
    val authorizationGrantId: UUID,
    val authorizationConsumptionId: UUID,
    val recordedAt: OffsetDateTime,
)

private data class ExecutionAttemptRow(
    val commandId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val admittedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
