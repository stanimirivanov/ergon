package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpCursor
import org.ergon.controlplane.followup.application.ResolverOwnedHumanFollowUpWork
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpClaimSnapshot
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.HumanFollowUpWorkItemSnapshot
import org.ergon.followup.domain.HumanFollowUpWorkItemStatus
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for immutable, resolver-attributed follow-up claims. */
@Repository
class PostgresHumanFollowUpClaimRepository(
    private val jdbcClient: JdbcClient,
) : HumanFollowUpClaimRepository {
    override fun lockOpenWorkItem(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
    ): Boolean =
        jdbcClient
            .sql(
                """
                SELECT TRUE
                FROM human_follow_up_work_items
                WHERE tenant_id = :tenantId
                    AND work_item_id = :workItemId
                    AND status = 'OPEN'
                FOR UPDATE
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .query(Boolean::class.java)
            .optional()
            .orElse(false)

    override fun findByWorkItem(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
    ): StoredHumanFollowUpClaim? =
        queryBase(
            """
            WHERE claim.tenant_id = :tenantId
                AND claim.work_item_id = :workItemId
            """.trimIndent(),
        ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .query(DataClassRowMapper(HumanFollowUpClaimRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredClaim()

    override fun create(
        tenantId: TenantId,
        claim: HumanFollowUpClaim,
    ): StoredHumanFollowUpClaim {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO human_follow_up_claims (
                        tenant_id, claim_id, work_item_id, resolver_actor_id,
                        authority_evidence_id, claimed_at
                    ) VALUES (
                        :tenantId, :claimId, :workItemId, :resolverActorId,
                        :authorityEvidenceId, :claimedAt
                    )
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("claimId", claim.id.value)
                .param("workItemId", claim.workItemId.value)
                .param("resolverActorId", claim.resolverActorId.value)
                .param("authorityEvidenceId", claim.authorityEvidenceId.value)
                .param("claimedAt", claim.claimedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .single()
        return StoredHumanFollowUpClaim(claim, recordedAt.toInstant())
    }

    override fun findForResolver(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        claimId: HumanFollowUpClaimId,
        actorId: HumanActorId,
        at: Instant,
    ): StoredHumanFollowUpClaim? =
        queryBase(
            """
            WHERE claim.tenant_id = :tenantId
                AND claim.work_item_id = :workItemId
                AND claim.claim_id = :claimId
                AND EXISTS (
                    SELECT 1
                    FROM approval_authority_evidence authority
                    WHERE authority.tenant_id = claim.tenant_id
                        AND authority.actor_id = :actorId
                        AND authority.authority = 'RESOLVER'
                        AND authority.case_id IS NULL
                        AND authority.attested_at <= :at
                        AND authority.expires_at > :at
                )
            """.trimIndent(),
        ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .param("claimId", claimId.value)
            .param("actorId", actorId.value)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .query(DataClassRowMapper(HumanFollowUpClaimRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredClaim()

    override fun listOwnedForResolver(
        tenantId: TenantId,
        actorId: HumanActorId,
        at: Instant,
        after: ResolverOwnedHumanFollowUpCursor?,
        limit: Int,
    ): List<ResolverOwnedHumanFollowUpWork> {
        require(limit > 0) { "resolver-owned human follow-up limit must be positive" }
        val cursorPredicate =
            if (after == null) {
                ""
            } else {
                "AND (claim.claimed_at, claim.claim_id) > (:afterClaimedAt, :afterClaimId)"
            }
        var statement =
            ownedWorkQuery(cursorPredicate)
                .param("tenantId", tenantId.value)
                .param("actorId", actorId.value)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
        if (after != null) {
            statement =
                statement
                    .param("afterClaimedAt", after.claimedAt.atOffset(ZoneOffset.UTC))
                    .param("afterClaimId", after.claimId.value)
        }
        return statement
            .query(DataClassRowMapper(ResolverOwnedHumanFollowUpRow::class.java))
            .list()
            .map(ResolverOwnedHumanFollowUpRow::toOwnedWork)
    }

    private fun ownedWorkQuery(cursorPredicate: String): JdbcClient.StatementSpec =
        jdbcClient.sql(
            """
            SELECT
                item.work_item_id,
                run.case_id,
                item.run_id,
                item.escalation_event_id,
                item.reason,
                item.queue_key,
                item.status,
                item.opened_at,
                item.recorded_at AS work_item_recorded_at,
                claim.claim_id,
                claim.resolver_actor_id,
                claim.authority_evidence_id,
                claim.claimed_at,
                claim.recorded_at AS claim_recorded_at
            FROM human_follow_up_claims claim
            JOIN human_follow_up_work_items item
                ON item.tenant_id = claim.tenant_id
                AND item.work_item_id = claim.work_item_id
            JOIN resolution_runs run
                ON run.tenant_id = item.tenant_id
                AND run.run_id = item.run_id
            WHERE claim.tenant_id = :tenantId
                AND claim.resolver_actor_id = :actorId
                AND item.status = 'OPEN'
                AND EXISTS (
                    SELECT 1
                    FROM approval_authority_evidence authority
                    WHERE authority.tenant_id = claim.tenant_id
                        AND authority.actor_id = :actorId
                        AND authority.authority = 'RESOLVER'
                        AND authority.case_id IS NULL
                        AND authority.attested_at <= :at
                        AND authority.expires_at > :at
                )
                $cursorPredicate
            ORDER BY claim.claimed_at, claim.claim_id
            LIMIT :limit
            """.trimIndent(),
        )

    private fun queryBase(whereClause: String): JdbcClient.StatementSpec =
        jdbcClient.sql(
            """
            SELECT
                claim.claim_id,
                claim.work_item_id,
                claim.resolver_actor_id,
                claim.authority_evidence_id,
                claim.claimed_at,
                claim.recorded_at
            FROM human_follow_up_claims claim
            $whereClause
            """.trimIndent(),
        )
}

private fun HumanFollowUpClaimRow.toStoredClaim(): StoredHumanFollowUpClaim =
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
        recordedAt.toInstant(),
    )

private data class HumanFollowUpClaimRow(
    val claimId: UUID,
    val workItemId: UUID,
    val resolverActorId: UUID,
    val authorityEvidenceId: UUID,
    val claimedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)

private fun ResolverOwnedHumanFollowUpRow.toOwnedWork(): ResolverOwnedHumanFollowUpWork =
    try {
        ResolverOwnedHumanFollowUpWork(
            StoredHumanFollowUpWorkItem(
                HumanFollowUpWorkItem.rehydrate(
                    HumanFollowUpWorkItemSnapshot(
                        HumanFollowUpWorkItemId(workItemId),
                        CaseId(caseId),
                        ResolutionRunId(runId),
                        ResolutionRunEventId(escalationEventId),
                        ResolutionRunEscalationReason.valueOf(reason),
                        HumanFollowUpQueueKey.of(queueKey),
                        HumanFollowUpWorkItemStatus.valueOf(status),
                        openedAt.toInstant(),
                    ),
                ),
                workItemRecordedAt.toInstant(),
            ),
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
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored resolver-owned human follow-up work is invalid", exception)
    }

private data class ResolverOwnedHumanFollowUpRow(
    val workItemId: UUID,
    val caseId: UUID,
    val runId: UUID,
    val escalationEventId: UUID,
    val reason: String,
    val queueKey: String,
    val status: String,
    val openedAt: OffsetDateTime,
    val workItemRecordedAt: OffsetDateTime,
    val claimId: UUID,
    val resolverActorId: UUID,
    val authorityEvidenceId: UUID,
    val claimedAt: OffsetDateTime,
    val claimRecordedAt: OffsetDateTime,
)
