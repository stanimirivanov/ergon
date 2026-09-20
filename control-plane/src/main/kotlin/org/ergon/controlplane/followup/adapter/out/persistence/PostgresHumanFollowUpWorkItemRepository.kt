package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.HumanFollowUpInboxCriteria
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.HumanFollowUpWorkItemSnapshot
import org.ergon.followup.domain.HumanFollowUpWorkItemStatus
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
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

/** PostgreSQL adapter for durable, tenant-scoped human follow-up work. */
@Repository
class PostgresHumanFollowUpWorkItemRepository(
    private val jdbcClient: JdbcClient,
) : HumanFollowUpWorkItemRepository {
    override fun create(
        tenantId: TenantId,
        item: HumanFollowUpWorkItem,
    ): StoredHumanFollowUpWorkItem {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items (
                        tenant_id, work_item_id, run_id, escalation_event_id,
                        reason, queue_key, status, opened_at
                    )
                    SELECT
                        :tenantId, :workItemId, run_id, :escalationEventId,
                        :reason, :queueKey, :status, :openedAt
                    FROM resolution_runs
                    WHERE tenant_id = :tenantId AND run_id = :runId AND case_id = :caseId
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("workItemId", item.id.value)
                .param("caseId", item.caseId.value)
                .param("runId", item.runId.value)
                .param("escalationEventId", item.escalationEventId.value)
                .param("reason", item.reason.name)
                .param("queueKey", item.queueKey.value)
                .param("status", item.status.name)
                .param("openedAt", item.openedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: error("human follow-up source run does not match its case")
        return StoredHumanFollowUpWorkItem(item, recordedAt.toInstant())
    }

    override fun findByEscalation(
        tenantId: TenantId,
        escalationEventId: ResolutionRunEventId,
    ): StoredHumanFollowUpWorkItem? =
        queryBase(
            """
            WHERE item.tenant_id = :tenantId
                AND item.escalation_event_id = :escalationEventId
            """.trimIndent(),
        ).param("tenantId", tenantId.value)
            .param("escalationEventId", escalationEventId.value)
            .query(DataClassRowMapper(HumanFollowUpWorkItemRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredWorkItem()

    override fun findForResolver(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        actorId: HumanActorId,
        at: Instant,
    ): StoredHumanFollowUpWorkItem? =
        queryBase(
            """
            WHERE item.tenant_id = :tenantId
                AND item.work_item_id = :workItemId
                AND EXISTS (
                    SELECT 1
                    FROM approval_authority_evidence authority
                    WHERE authority.tenant_id = item.tenant_id
                        AND authority.actor_id = :actorId
                        AND authority.authority = 'RESOLVER'
                        AND authority.case_id IS NULL
                        AND authority.attested_at <= :at
                        AND authority.expires_at > :at
                )
            """.trimIndent(),
        ).param("tenantId", tenantId.value)
            .param("workItemId", workItemId.value)
            .param("actorId", actorId.value)
            .param("at", at.atOffset(ZoneOffset.UTC))
            .query(DataClassRowMapper(HumanFollowUpWorkItemRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredWorkItem()

    override fun listOpenForResolver(criteria: HumanFollowUpInboxCriteria): List<StoredHumanFollowUpWorkItem> {
        require(criteria.limit > 0) { "human follow-up inbox limit must be positive" }
        val cursorPredicate =
            if (criteria.after == null) {
                ""
            } else {
                "AND (item.opened_at, item.work_item_id) > (:afterOpenedAt, :afterWorkItemId)"
            }
        val queuePredicate = if (criteria.queueKey == null) "" else "AND item.queue_key = :queueKey"
        var statement =
            queryBase(
                """
                WHERE item.tenant_id = :tenantId
                    AND item.status = 'OPEN'
                    AND NOT EXISTS (
                        SELECT 1
                        FROM human_follow_up_claims claim
                        WHERE claim.tenant_id = item.tenant_id
                            AND claim.work_item_id = item.work_item_id
                    )
                    AND EXISTS (
                        SELECT 1
                        FROM approval_authority_evidence authority
                        WHERE authority.tenant_id = item.tenant_id
                            AND authority.actor_id = :actorId
                            AND authority.authority = 'RESOLVER'
                            AND authority.case_id IS NULL
                            AND authority.attested_at <= :at
                            AND authority.expires_at > :at
                    )
                    $queuePredicate
                    $cursorPredicate
                ORDER BY item.opened_at, item.work_item_id
                LIMIT :limit
                """.trimIndent(),
            ).param("tenantId", criteria.tenantId.value)
                .param("actorId", criteria.actorId.value)
                .param("at", criteria.at.atOffset(ZoneOffset.UTC))
                .param("limit", criteria.limit)
        if (criteria.queueKey != null) {
            statement = statement.param("queueKey", criteria.queueKey.value)
        }
        if (criteria.after != null) {
            statement =
                statement
                    .param("afterOpenedAt", criteria.after.openedAt.atOffset(ZoneOffset.UTC))
                    .param("afterWorkItemId", criteria.after.workItemId.value)
        }
        return statement
            .query(DataClassRowMapper(HumanFollowUpWorkItemRow::class.java))
            .list()
            .map(HumanFollowUpWorkItemRow::toStoredWorkItem)
    }

    private fun queryBase(whereClause: String): JdbcClient.StatementSpec =
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
                item.recorded_at
            FROM human_follow_up_work_items item
            JOIN resolution_runs run
                ON run.tenant_id = item.tenant_id AND run.run_id = item.run_id
            $whereClause
            """.trimIndent(),
        )
}

private fun HumanFollowUpWorkItemRow.toStoredWorkItem(): StoredHumanFollowUpWorkItem =
    try {
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
            recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored human follow-up work item is invalid", exception)
    }

private data class HumanFollowUpWorkItemRow(
    val workItemId: UUID,
    val caseId: UUID,
    val runId: UUID,
    val escalationEventId: UUID,
    val reason: String,
    val queueKey: String,
    val status: String,
    val openedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
