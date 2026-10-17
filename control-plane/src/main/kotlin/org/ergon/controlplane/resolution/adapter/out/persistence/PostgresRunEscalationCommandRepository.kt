package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.EscalateSupervisedRunCommand
import org.ergon.controlplane.resolution.application.RunEscalationCommandRepository
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredRunEscalationCommand
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Exact browser audit only; runtime and follow-up writes retain their existing capability owners. */
@Repository
class PostgresRunEscalationCommandRepository(
    private val jdbc: JdbcClient,
) : RunEscalationCommandRepository {
    override fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-escalation-command:${tenantId.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun find(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunEscalationCommand? =
        jdbc
            .sql(
                """
                SELECT tenant_id, command_id, run_id, actor_id, supervision_revision, supervised_at,
                    expected_run_version, source_attempt_number, retry_policy_revision, maximum_attempts,
                    reason, authority_evidence_id, escalation_event_id,
                    follow_up_work_item_id, requested_at, recorded_at
                FROM resolution_run_escalation_commands
                WHERE tenant_id = :tenant AND command_id = :command
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("command", commandId)
            .query(DataClassRowMapper(RunEscalationCommandRow::class.java))
            .optional()
            .getOrNull()
            ?.toStored()

    override fun create(
        tenantId: TenantId,
        command: StoredRunEscalationCommand,
    ): StoredRunEscalationCommand {
        val intent = command.command
        val recordedAt =
            jdbc
                .sql(
                    """
                    INSERT INTO resolution_run_escalation_commands (
                        tenant_id, command_id, run_id, actor_id, supervision_revision, supervised_at,
                        expected_run_version, source_attempt_number, retry_policy_revision, maximum_attempts,
                        reason, authority_evidence_id, escalation_event_id, follow_up_work_item_id, requested_at
                    ) VALUES (
                        :tenant, :command, :run, :actor, :revision, :supervisedAt,
                        :version, :attempt, :policy, :ceiling, :reason, :authority, :event, :work, :at
                    ) RETURNING recorded_at
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to tenantId.value,
                        "command" to intent.commandId,
                        "run" to intent.runId,
                        "actor" to intent.actorId,
                        "revision" to intent.expectedSupervisionRevision.value,
                        "supervisedAt" to command.supervisedAt.atOffset(ZoneOffset.UTC),
                        "version" to intent.expectedRunVersion,
                        "attempt" to intent.sourceAttemptNumber,
                        "policy" to intent.retryPolicyRevision,
                        "ceiling" to intent.maximumAttempts,
                        "reason" to intent.reason.name,
                        "authority" to command.authorityEvidenceId,
                        "event" to command.escalationEventId,
                        "work" to command.followUpWorkItemId,
                        "at" to command.requestedAt.atOffset(ZoneOffset.UTC),
                    ),
                ).query(OffsetDateTime::class.java)
                .single()
        return command.copy(recordedAt = recordedAt.toInstant())
    }
}

private data class RunEscalationCommandRow(
    val tenantId: UUID,
    val commandId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val supervisionRevision: Long,
    val supervisedAt: OffsetDateTime,
    val expectedRunVersion: Long,
    val sourceAttemptNumber: Int,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val reason: String,
    val authorityEvidenceId: UUID,
    val escalationEventId: UUID,
    val followUpWorkItemId: UUID,
    val requestedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toStored(): StoredRunEscalationCommand =
        StoredRunEscalationCommand(
            EscalateSupervisedRunCommand(
                tenantId,
                runId,
                actorId,
                commandId,
                RunSupervisionRevision(supervisionRevision),
                expectedRunVersion,
                sourceAttemptNumber,
                retryPolicyRevision,
                maximumAttempts,
                ResolutionRunEscalationReason.valueOf(reason),
            ),
            supervisedAt.toInstant(),
            authorityEvidenceId,
            escalationEventId,
            followUpWorkItemId,
            requestedAt.toInstant(),
            recordedAt.toInstant(),
        )
}
