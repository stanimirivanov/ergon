package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HumanFollowUpReleaseRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpRelease
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
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

/** PostgreSQL release-event and current-ownership projection adapter. */
@Repository
class PostgresHumanFollowUpReleaseRepository(
    private val jdbcClient: JdbcClient,
) : HumanFollowUpReleaseRepository {
    override fun findByClaim(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        claimId: HumanFollowUpClaimId,
    ): StoredHumanFollowUpRelease? =
        jdbcClient
            .sql(
                """
                SELECT work_item_id, claim_id, resolver_actor_id,
                    authority_evidence_id, ownership_revision,
                    occurred_at AS released_at, recorded_at
                FROM human_follow_up_ownership_events
                WHERE tenant_id = :tenantId
                    AND work_item_id = :workItemId
                    AND claim_id = :claimId
                    AND event_type = 'RELEASED'
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .param("claimId", claimId.value)
            .query(DataClassRowMapper(HumanFollowUpReleaseRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredRelease()

    override fun record(
        tenantId: TenantId,
        claim: StoredHumanFollowUpClaim,
        authorityEvidenceId: ApprovalAuthorityEvidenceId,
        revision: HumanFollowUpOwnershipRevision,
        releasedAt: Instant,
    ): StoredHumanFollowUpRelease {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO human_follow_up_ownership_events (
                        tenant_id, work_item_id, ownership_revision, event_type,
                        claim_id, resolver_actor_id, authority_evidence_id, occurred_at
                    ) VALUES (
                        :tenantId, :workItemId, :revision, 'RELEASED',
                        :claimId, :actorId, :evidenceId, :releasedAt
                    )
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("workItemId", claim.claim.workItemId.value)
                .param("revision", revision.value)
                .param("claimId", claim.claim.id.value)
                .param("actorId", claim.claim.resolverActorId.value)
                .param("evidenceId", authorityEvidenceId.value)
                .param("releasedAt", releasedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .single()
        val updated =
            jdbcClient
                .sql(
                    """
                    UPDATE human_follow_up_current_ownership
                    SET ownership_revision = :resultingRevision,
                        current_claim_id = NULL,
                        current_resolver_actor_id = NULL,
                        current_claimed_at = NULL
                    WHERE tenant_id = :tenantId
                        AND work_item_id = :workItemId
                        AND ownership_revision = :expectedRevision
                        AND current_claim_id = :claimId
                        AND current_resolver_actor_id = :actorId
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("workItemId", claim.claim.workItemId.value)
                .param("expectedRevision", revision.value - 1)
                .param("resultingRevision", revision.value)
                .param("claimId", claim.claim.id.value)
                .param("actorId", claim.claim.resolverActorId.value)
                .update()
        check(updated == 1) { "current ownership changed during release" }
        return StoredHumanFollowUpRelease(
            claim.claim.workItemId,
            claim.claim.id,
            claim.claim.resolverActorId,
            authorityEvidenceId,
            revision,
            releasedAt,
            recordedAt.toInstant(),
        )
    }
}

private fun HumanFollowUpReleaseRow.toStoredRelease(): StoredHumanFollowUpRelease =
    StoredHumanFollowUpRelease(
        HumanFollowUpWorkItemId(workItemId),
        HumanFollowUpClaimId(claimId),
        HumanActorId(resolverActorId),
        ApprovalAuthorityEvidenceId(authorityEvidenceId),
        HumanFollowUpOwnershipRevision(ownershipRevision),
        releasedAt.toInstant(),
        recordedAt.toInstant(),
    )

private data class HumanFollowUpReleaseRow(
    val workItemId: UUID,
    val claimId: UUID,
    val resolverActorId: UUID,
    val authorityEvidenceId: UUID,
    val ownershipRevision: Long,
    val releasedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
