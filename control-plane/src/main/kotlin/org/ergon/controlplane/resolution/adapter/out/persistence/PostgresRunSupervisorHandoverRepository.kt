package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.RunSupervisorHandover
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverRepository
import org.ergon.controlplane.resolution.application.StoredRunSupervisorHandover
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Durable handover receipts and their atomically advanced access projection; no initial fact is rewritten. */
@Repository
class PostgresRunSupervisorHandoverRepository(
    private val jdbcClient: JdbcClient,
) : RunSupervisorHandoverRepository {
    override fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    ) {
        // A run lock alone cannot serialize reuse of one command ID on different runs.
        jdbcClient
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-supervisor-handover:${tenantId.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun findByCommandId(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunSupervisorHandover? =
        jdbcClient
            .sql(
                """
                SELECT run_id, assignment_id, command_id, supervision_revision,
                    previous_actor_id, supervisor_actor_id, previous_authority_evidence_id,
                    authority_evidence_id, occurred_at, recorded_at
                FROM resolution_run_supervision_events
                WHERE tenant_id = :tenantId AND command_id = :commandId AND event_type = 'HANDED_OVER'
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("commandId", commandId)
            .query(DataClassRowMapper(HandoverRow::class.java))
            .optional()
            .getOrNull()
            ?.toStored()

    override fun record(
        tenantId: TenantId,
        handover: RunSupervisorHandover,
    ): StoredRunSupervisorHandover {
        val instants =
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_run_supervision_events (
                        tenant_id, run_id, supervision_revision, assignment_id, event_type,
                        supervisor_actor_id, authority_evidence_id, previous_actor_id,
                        previous_authority_evidence_id, command_id, occurred_at
                    ) VALUES (
                        :tenantId, :runId, :revision, :assignmentId, 'HANDED_OVER',
                        :incomingActor, :incomingEvidence, :outgoingActor,
                        :outgoingEvidence, :commandId, :occurredAt
                    ) RETURNING occurred_at, recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("runId", handover.runId.value)
                .param("revision", handover.revision.value)
                .param("assignmentId", handover.assignmentId)
                .param("incomingActor", handover.supervisorActorId.value)
                .param("incomingEvidence", handover.authorityEvidenceId.value)
                .param("outgoingActor", handover.previousActorId.value)
                .param("outgoingEvidence", handover.previousAuthorityEvidenceId.value)
                .param("commandId", handover.commandId)
                .param("occurredAt", handover.occurredAt.atOffset(ZoneOffset.UTC))
                .query(DataClassRowMapper(HandoverInstantsRow::class.java))
                .single()
        val updated =
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_run_current_supervision
                    SET supervision_revision = :revision,
                        supervisor_actor_id = :incomingActor, supervised_at = :occurredAt
                    WHERE tenant_id = :tenantId AND run_id = :runId
                        AND supervision_revision = :previousRevision AND supervisor_actor_id = :outgoingActor
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("runId", handover.runId.value)
                .param("revision", handover.revision.value)
                .param("previousRevision", handover.revision.value - 1)
                .param("incomingActor", handover.supervisorActorId.value)
                .param("outgoingActor", handover.previousActorId.value)
                .param("occurredAt", instants.occurredAt)
                .update()
        check(updated == 1) { "run supervision projection disagrees with its handover" }
        // PostgreSQL stores microseconds. Return the stored instant even on the
        // first call so its receipt equals a later read/replay exactly.
        return StoredRunSupervisorHandover(
            handover.copy(occurredAt = instants.occurredAt.toInstant()),
            instants.recordedAt.toInstant(),
        )
    }
}

private data class HandoverInstantsRow(
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)

private data class HandoverRow(
    val runId: UUID,
    val assignmentId: UUID,
    val commandId: UUID,
    val supervisionRevision: Long,
    val previousActorId: UUID,
    val supervisorActorId: UUID,
    val previousAuthorityEvidenceId: UUID,
    val authorityEvidenceId: UUID,
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toStored(): StoredRunSupervisorHandover =
        StoredRunSupervisorHandover(
            RunSupervisorHandover(
                ResolutionRunId(runId),
                assignmentId,
                commandId,
                RunSupervisionRevision(supervisionRevision),
                HumanActorId(previousActorId),
                HumanActorId(supervisorActorId),
                ApprovalAuthorityEvidenceId(previousAuthorityEvidenceId),
                ApprovalAuthorityEvidenceId(authorityEvidenceId),
                occurredAt.toInstant(),
            ),
            recordedAt.toInstant(),
        )
}
