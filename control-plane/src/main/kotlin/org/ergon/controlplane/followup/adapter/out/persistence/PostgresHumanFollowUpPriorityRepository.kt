package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HumanFollowUpPriorityCommand
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpPriority
import org.ergon.followup.domain.HumanFollowUpPriority
import org.ergon.followup.domain.HumanFollowUpPriorityRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Uses the caller's work-row lock; the immutable assignment position reconstructs its expected predecessor. */
@Repository
class PostgresHumanFollowUpPriorityRepository(
    private val jdbc: JdbcClient,
) : HumanFollowUpPriorityRepository {
    override fun find(command: HumanFollowUpPriorityCommand): StoredHumanFollowUpPriority? =
        jdbc
            .sql(
                """
                SELECT tenant_id, work_item_id, command_id, claim_id, ownership_revision, actor_id,
                    priority_revision, priority, assigned_at, recorded_at
                FROM human_follow_up_priority_assignments
                WHERE tenant_id = :tenant AND work_item_id = :work AND command_id = :command
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .param("command", command.commandId)
            .query(DataClassRowMapper(PriorityRow::class.java))
            .optional()
            .getOrNull()
            ?.toReceipt()

    override fun currentRevision(
        tenant: TenantId,
        work: HumanFollowUpWorkItemId,
    ): HumanFollowUpPriorityRevision =
        HumanFollowUpPriorityRevision(
            jdbc
                .sql(
                    """
                    SELECT COALESCE(MAX(priority_revision), 0) FROM human_follow_up_priority_assignments
                    WHERE tenant_id = :tenant AND work_item_id = :work
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("work", work.value)
                .query(Long::class.java)
                .single(),
        )

    override fun record(
        command: HumanFollowUpPriorityCommand,
        evidenceId: ApprovalAuthorityEvidenceId,
        at: Instant,
    ): StoredHumanFollowUpPriority {
        jdbc
            .sql(
                """
                INSERT INTO human_follow_up_priority_assignments (
                    tenant_id, work_item_id, command_id, claim_id, ownership_revision, actor_id,
                    priority_revision, priority, authority_evidence_id, assigned_at
                ) VALUES (:tenant, :work, :command, :claim, :ownership, :actor, :revision, :priority, :evidence, :at)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .param("command", command.commandId)
            .param("claim", command.claimId)
            .param("ownership", command.expectedOwnershipRevision)
            .param("actor", command.actorId)
            .param("revision", HumanFollowUpPriorityRevision(command.expectedPriorityRevision).next().value)
            .param("priority", command.priority.name)
            .param("evidence", evidenceId.value)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .update()
        return checkNotNull(find(command)) { "priority receipt was not recorded" }
    }
}

private data class PriorityRow(
    val tenantId: UUID,
    val workItemId: UUID,
    val commandId: UUID,
    val claimId: UUID,
    val ownershipRevision: Long,
    val actorId: UUID,
    val priorityRevision: Long,
    val priority: String,
    val assignedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toReceipt(): StoredHumanFollowUpPriority =
        StoredHumanFollowUpPriority(
            HumanFollowUpPriorityCommand(
                tenantId,
                workItemId,
                actorId,
                commandId,
                claimId,
                ownershipRevision,
                priorityRevision - 1,
                HumanFollowUpPriority.valueOf(priority),
            ),
            HumanFollowUpPriorityRevision(priorityRevision),
            assignedAt.toInstant(),
            recordedAt.toInstant(),
        )
}
