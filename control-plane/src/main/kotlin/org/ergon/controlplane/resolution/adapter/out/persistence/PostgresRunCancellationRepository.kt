package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.CancelSupervisedRunCommand
import org.ergon.controlplane.resolution.application.RunCancellationRepository
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredRunCancellation
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunCancelled
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Appends terminal event, current projection and semantically linked audit in the caller's transaction. */
@Repository
class PostgresRunCancellationRepository(
    private val jdbc: JdbcClient,
) : RunCancellationRepository {
    override fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-cancellation-command:${tenantId.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun find(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunCancellation? =
        jdbc
            .sql(
                """
                SELECT command.run_id, command.actor_id, command.supervision_revision,
                    command.supervised_at, command.expected_run_version, command.authority_evidence_id,
                    command.event_id, command.cancelled_at, command.recorded_at, event.from_state
                FROM resolution_run_cancellation_commands command
                JOIN resolution_run_events event ON event.tenant_id = command.tenant_id
                    AND event.run_id = command.run_id AND event.event_id = command.event_id
                WHERE command.tenant_id = :tenant AND command.command_id = :command
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("command", commandId)
            .query { row, _ ->
                StoredRunCancellation(
                    CancelSupervisedRunCommand(
                        tenantId.value,
                        row.getObject("run_id", UUID::class.java),
                        row.getObject("actor_id", UUID::class.java),
                        commandId,
                        RunSupervisionRevision(row.getLong("supervision_revision")),
                        row.getLong("expected_run_version"),
                    ),
                    row.getObject("supervised_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("authority_evidence_id", UUID::class.java),
                    ResolutionRunCancelled(
                        ResolutionRunEventId(row.getObject("event_id", UUID::class.java)),
                        ResolutionRunId(row.getObject("run_id", UUID::class.java)),
                        ResolutionRunState.valueOf(row.getString("from_state")),
                        row.getObject("cancelled_at", OffsetDateTime::class.java).toInstant(),
                    ),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .getOrNull()

    override fun hasExecutionHistory(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (SELECT 1 FROM capability_authorization_grants WHERE tenant_id = :tenant AND run_id = :run)
                    OR EXISTS (SELECT 1 FROM capability_authorization_consumptions WHERE tenant_id = :tenant AND run_id = :run)
                    OR EXISTS (SELECT 1 FROM capability_invocation_receipts WHERE tenant_id = :tenant AND run_id = :run)
                    OR EXISTS (SELECT 1 FROM resolution_run_execution_commands WHERE tenant_id = :tenant AND run_id = :run)
                    OR EXISTS (SELECT 1 FROM resolution_run_execution_attempts WHERE tenant_id = :tenant AND run_id = :run)
                    OR EXISTS (SELECT 1 FROM resolution_run_events WHERE tenant_id = :tenant AND run_id = :run)
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("run", runId.value)
            .query(Boolean::class.java)
            .single()

    override fun append(
        tenantId: TenantId,
        cancellation: StoredRunCancellation,
    ): StoredRunCancellation {
        val command = cancellation.command
        val event = cancellation.event
        val params =
            mapOf(
                "tenant" to tenantId.value,
                "run" to command.runId,
                "event" to event.id.value,
                "from" to event.fromState.name,
                "at" to event.occurredAt.atOffset(ZoneOffset.UTC),
            )
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_events (tenant_id, run_id, sequence, event_id, event_type, from_state, to_state, occurred_at)
                VALUES (:tenant, :run, 1, :event, 'RUN_CANCELLED', :from, 'CANCELLED', :at)
                """.trimIndent(),
            ).params(params)
            .update()
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE resolution_run_states SET state = 'CANCELLED', version = 1, updated_at = :at
                    WHERE tenant_id = :tenant AND run_id = :run AND version = 0 AND state = :from
                    """.trimIndent(),
                ).params(params.filterKeys { it != "event" })
                .update()
        check(updated == 1) { "run state changed before cancellation projection" }
        val recordedAt =
            jdbc
                .sql(
                    """
                    INSERT INTO resolution_run_cancellation_commands (tenant_id, command_id, run_id, actor_id,
                        supervision_revision, supervised_at, expected_run_version, authority_evidence_id, event_id, cancelled_at)
                    VALUES (:tenant, :command, :run, :actor, :revision, :supervised, 0, :authority, :event, :at)
                    RETURNING recorded_at
                    """.trimIndent(),
                ).params(
                    params.filterKeys { it != "from" } +
                        mapOf(
                            "command" to command.commandId,
                            "actor" to command.actorId,
                            "revision" to command.expectedSupervisionRevision.value,
                            "supervised" to cancellation.supervisedAt.atOffset(ZoneOffset.UTC),
                            "authority" to cancellation.authorityEvidenceId,
                        ),
                ).query(OffsetDateTime::class.java)
                .single()
        return cancellation.copy(recordedAt = recordedAt.toInstant())
    }
}
