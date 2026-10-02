package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommandRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaimCommand
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimCommandId
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpClaimSnapshot
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for claim-command receipts and revisioned ownership. */
@Repository
class PostgresHumanFollowUpClaimCommandRepository(
    private val jdbcClient: JdbcClient,
) : HumanFollowUpClaimCommandRepository {
    override fun findCommand(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        commandId: HumanFollowUpClaimCommandId,
    ): StoredHumanFollowUpClaimCommand? =
        jdbcClient
            .sql(
                """
                SELECT
                    receipt.command_id,
                    receipt.expected_ownership_revision,
                    receipt.resulting_ownership_revision,
                    event.event_type,
                    event.claim_id,
                    event.work_item_id,
                    event.resolver_actor_id,
                    event.authority_evidence_id,
                    event.occurred_at AS claimed_at,
                    event.recorded_at AS claim_recorded_at
                FROM human_follow_up_claim_commands receipt
                JOIN human_follow_up_ownership_events event
                    ON event.tenant_id = receipt.tenant_id
                    AND event.work_item_id = receipt.work_item_id
                    AND event.ownership_revision = receipt.resulting_ownership_revision
                WHERE receipt.tenant_id = :tenantId
                    AND receipt.work_item_id = :workItemId
                    AND receipt.command_id = :commandId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .param("commandId", commandId.value)
            .query(DataClassRowMapper(HumanFollowUpClaimCommandRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredCommand()

    override fun currentOwnershipRevision(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
    ): HumanFollowUpOwnershipRevision =
        HumanFollowUpOwnershipRevision(
            jdbcClient
                .sql(
                    """
                    SELECT ownership_revision
                    FROM human_follow_up_current_ownership
                    WHERE tenant_id = :tenantId AND work_item_id = :workItemId
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("workItemId", workItemId.value)
                .query(Long::class.java)
                .optional()
                .getOrNull() ?: 0L,
        )

    override fun recordCommand(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        commandId: HumanFollowUpClaimCommandId,
        expectedRevision: HumanFollowUpOwnershipRevision,
        resultingRevision: HumanFollowUpOwnershipRevision,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO human_follow_up_claim_commands (
                    tenant_id, work_item_id, command_id,
                    expected_ownership_revision, resulting_ownership_revision
                ) VALUES (
                    :tenantId, :workItemId, :commandId,
                    :expectedRevision, :resultingRevision
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .param("commandId", commandId.value)
            .param("expectedRevision", expectedRevision.value)
            .param("resultingRevision", resultingRevision.value)
            .update()
    }
}

private fun HumanFollowUpClaimCommandRow.toStoredCommand(): StoredHumanFollowUpClaimCommand {
    check(eventType == "CLAIMED") { "claim command receipt points to a non-claim ownership event" }
    return StoredHumanFollowUpClaimCommand(
        HumanFollowUpClaimCommandId(commandId),
        HumanFollowUpOwnershipRevision(expectedOwnershipRevision),
        HumanFollowUpOwnershipRevision(resultingOwnershipRevision),
        StoredHumanFollowUpClaim(
            HumanFollowUpClaim.rehydrate(
                HumanFollowUpClaimSnapshot(
                    HumanFollowUpClaimId(claimId),
                    HumanFollowUpWorkItemId(workItemId),
                    HumanActorId(resolverActorId),
                    ApprovalAuthorityEvidenceId(authorityEvidenceId),
                    claimedAt.toInstant(),
                ),
            ),
            claimRecordedAt.toInstant(),
        ),
    )
}

private data class HumanFollowUpClaimCommandRow(
    val commandId: UUID,
    val expectedOwnershipRevision: Long,
    val resultingOwnershipRevision: Long,
    val eventType: String,
    val claimId: UUID,
    val workItemId: UUID,
    val resolverActorId: UUID,
    val authorityEvidenceId: UUID,
    val claimedAt: OffsetDateTime,
    val claimRecordedAt: OffsetDateTime,
)
