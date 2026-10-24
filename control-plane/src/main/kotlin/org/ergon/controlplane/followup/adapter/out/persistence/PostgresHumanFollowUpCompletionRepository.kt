package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HumanFollowUpCompletionCommand
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpCompletion
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Uses the caller's work-row lock; constraint violations remain defects, not presumed idempotent success. */
@Repository
class PostgresHumanFollowUpCompletionRepository(
    private val jdbc: JdbcClient,
) : HumanFollowUpCompletionRepository {
    override fun find(command: HumanFollowUpCompletionCommand): StoredHumanFollowUpCompletion? =
        jdbc
            .sql(
                """
                SELECT tenant_id, work_item_id, command_id, claim_id, ownership_revision,
                    actor_id, completed_at, recorded_at
                FROM human_follow_up_completions
                WHERE tenant_id = :tenant AND work_item_id = :work AND command_id = :command
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .param("command", command.commandId)
            .query(DataClassRowMapper(CompletionRow::class.java))
            .optional()
            .getOrNull()
            ?.toReceipt()

    override fun record(
        command: HumanFollowUpCompletionCommand,
        evidenceId: ApprovalAuthorityEvidenceId,
        at: Instant,
    ): StoredHumanFollowUpCompletion {
        jdbc
            .sql(
                """
                INSERT INTO human_follow_up_completions (
                    tenant_id, work_item_id, command_id, claim_id, ownership_revision,
                    actor_id, authority_evidence_id, completed_at
                ) VALUES (:tenant, :work, :command, :claim, :revision, :actor, :evidence, :at)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .param("command", command.commandId)
            .param("claim", command.claimId)
            .param("revision", command.expectedOwnershipRevision)
            .param("actor", command.actorId)
            .param("evidence", evidenceId.value)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .update()
        return checkNotNull(find(command)) { "completion receipt was not recorded" }
    }
}

private data class CompletionRow(
    val tenantId: UUID,
    val workItemId: UUID,
    val commandId: UUID,
    val claimId: UUID,
    val ownershipRevision: Long,
    val actorId: UUID,
    val completedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toReceipt(): StoredHumanFollowUpCompletion =
        StoredHumanFollowUpCompletion(
            HumanFollowUpCompletionCommand(tenantId, workItemId, actorId, commandId, claimId, ownershipRevision),
            completedAt.toInstant(),
            recordedAt.toInstant(),
        )
}
