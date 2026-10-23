package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HumanFollowUpHandoverCommand
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpHandover
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** All writes enlist in the caller's work-item-locked transaction; no projection gap is committed. */
@Repository
class PostgresHumanFollowUpHandoverRepository(
    private val jdbc: JdbcClient,
) : HumanFollowUpHandoverRepository {
    override fun find(command: HumanFollowUpHandoverCommand): StoredHumanFollowUpHandover? =
        scoped(
            """
            SELECT tenant_id, work_item_id, command_id, sender_actor_id, original_claim_id,
                expected_ownership_revision, recipient_actor_id, successor_claim_id,
                ownership_revision, handed_over_at, recorded_at
            FROM human_follow_up_handovers
            WHERE tenant_id = :tenant AND work_item_id = :work AND command_id = :command
            """.trimIndent(),
            command,
        ).query(DataClassRowMapper(HandoverRow::class.java)).optional().getOrNull()?.toReceipt()

    override fun record(
        command: HumanFollowUpHandoverCommand,
        successor: HumanFollowUpClaim,
        senderEvidenceId: ApprovalAuthorityEvidenceId,
    ): StoredHumanFollowUpHandover {
        val revision = command.expectedOwnershipRevision + 2
        scoped(
            """
            INSERT INTO human_follow_up_ownership_events (
                tenant_id, work_item_id, ownership_revision, event_type,
                claim_id, resolver_actor_id, authority_evidence_id, occurred_at
            ) VALUES (:tenant, :work, :revision, 'CLAIMED', :claim, :actor, :evidence, :at)
            """.trimIndent(),
            command,
        ).param("revision", revision)
            .param("claim", successor.id.value)
            .param("actor", successor.resolverActorId.value)
            .param("evidence", successor.authorityEvidenceId.value)
            .param("at", successor.claimedAt.atOffset(ZoneOffset.UTC))
            .update()
        val changed =
            scoped(
                """
                UPDATE human_follow_up_current_ownership
                SET ownership_revision = :revision, current_claim_id = :claim,
                    current_resolver_actor_id = :recipient, current_claimed_at = :at
                WHERE tenant_id = :tenant AND work_item_id = :work
                    AND ownership_revision = :expected AND current_claim_id = :original
                    AND current_resolver_actor_id = :sender
                """.trimIndent(),
                command,
            ).param("revision", revision)
                .param("claim", successor.id.value)
                .param("recipient", command.recipientActorId)
                .param("at", successor.claimedAt.atOffset(ZoneOffset.UTC))
                .param("expected", command.expectedOwnershipRevision)
                .param("original", command.claimId)
                .param("sender", command.actorId)
                .update()
        check(changed == 1) { "ownership changed during locked handover" }
        scoped(
            """
            INSERT INTO human_follow_up_handovers (
                tenant_id, work_item_id, command_id, sender_actor_id, sender_authority_evidence_id,
                original_claim_id, expected_ownership_revision, recipient_actor_id,
                successor_claim_id, ownership_revision, handed_over_at
            ) VALUES (
                :tenant, :work, :command, :sender, :evidence,
                :original, :expected, :recipient, :claim, :revision, :at
            )
            """.trimIndent(),
            command,
        ).param("sender", command.actorId)
            .param("evidence", senderEvidenceId.value)
            .param("original", command.claimId)
            .param("expected", command.expectedOwnershipRevision)
            .param("recipient", command.recipientActorId)
            .param("claim", successor.id.value)
            .param("revision", revision)
            .param("at", successor.claimedAt.atOffset(ZoneOffset.UTC))
            .update()
        return checkNotNull(find(command)) { "handover receipt was not recorded" }
    }

    private fun scoped(
        sql: String,
        command: HumanFollowUpHandoverCommand,
    ): JdbcClient.StatementSpec =
        jdbc
            .sql(sql)
            .param("tenant", command.tenantId)
            .param("work", command.workItemId)
            .param("command", command.commandId)
}

private data class HandoverRow(
    val tenantId: UUID,
    val workItemId: UUID,
    val commandId: UUID,
    val senderActorId: UUID,
    val originalClaimId: UUID,
    val expectedOwnershipRevision: Long,
    val recipientActorId: UUID,
    val successorClaimId: UUID,
    val ownershipRevision: Long,
    val handedOverAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toReceipt(): StoredHumanFollowUpHandover =
        StoredHumanFollowUpHandover(
            HumanFollowUpHandoverCommand(
                tenantId,
                workItemId,
                senderActorId,
                commandId,
                originalClaimId,
                expectedOwnershipRevision,
                recipientActorId,
            ),
            HumanFollowUpClaimId(successorClaimId),
            HumanFollowUpOwnershipRevision(ownershipRevision),
            handedOverAt.toInstant(),
            recordedAt.toInstant(),
        )
}
