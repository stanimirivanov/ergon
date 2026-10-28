package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.controlplane.followup.application.HUMAN_NOTIFICATIONS_LIMIT
import org.ergon.controlplane.followup.application.HumanNotification
import org.ergon.controlplane.followup.application.HumanNotificationAudience
import org.ergon.controlplane.followup.application.HumanNotificationKind
import org.ergon.controlplane.followup.application.HumanNotificationReader
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Immutable-parent joins preserve historical recipients; no current-target projection or private text is read. */
@Repository
class PostgresHumanNotificationReader(
    private val jdbc: JdbcClient,
) : HumanNotificationReader {
    override fun latest(
        tenant: TenantId,
        recipient: HumanActorId,
        audience: HumanNotificationAudience,
        limit: Int,
    ): List<HumanNotification> {
        require(limit in 1..(HUMAN_NOTIFICATIONS_LIMIT + 1))
        val sources =
            when (audience) {
                HumanNotificationAudience.REQUESTER -> REQUESTER
                HumanNotificationAudience.RESOLVER -> RESOLVER
            }
        return jdbc
            .sql(
                """
                SELECT kind, source_id, target_id, delivered_at FROM ($sources) deliveries
                ORDER BY delivered_at DESC, kind COLLATE "C" DESC, source_id DESC, target_id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("actor", recipient.value)
            .param("limit", limit)
            .query(DataClassRowMapper(NotificationRow::class.java))
            .list()
            .map {
                HumanNotification(
                    HumanNotificationKind.valueOf(it.kind),
                    it.sourceId,
                    it.targetId,
                    it.deliveredAt.toInstant(),
                )
            }
    }

    private companion object {
        val REQUESTER =
            """
            SELECT 'INFORMATION_REQUESTED' AS kind, n.request_id AS source_id,
                q.case_id AS target_id, n.delivered_at
            FROM requester_information_request_notifications n
            JOIN requester_information_requests q ON q.tenant_id = n.tenant_id AND q.request_id = n.request_id
            JOIN case_requesters c ON c.tenant_id = q.tenant_id AND c.case_id = q.case_id
            WHERE n.tenant_id = :tenant AND c.requester_actor_id = :actor
            """.trimIndent()
        val RESOLVER =
            """
            SELECT 'FOLLOW_UP_HANDED_OVER' AS kind, command_id AS source_id,
                work_item_id AS target_id, delivered_at
            FROM human_follow_up_handover_notifications
            WHERE tenant_id = :tenant AND recipient_actor_id = :actor
            UNION ALL
            SELECT 'INFORMATION_ANSWERED' AS kind, n.response_id AS source_id,
                q.work_item_id AS target_id, n.delivered_at
            FROM requester_information_response_notifications n
            JOIN requester_information_responses r ON r.tenant_id = n.tenant_id AND r.response_id = n.response_id
            JOIN requester_information_requests q ON q.tenant_id = r.tenant_id AND q.request_id = r.request_id
            JOIN human_follow_up_ownership_events e ON e.tenant_id = q.tenant_id AND e.work_item_id = q.work_item_id
                AND e.ownership_revision = n.recipient_ownership_revision AND e.event_type = 'CLAIMED'
            WHERE n.tenant_id = :tenant AND e.resolver_actor_id = :actor
            """.trimIndent()
    }
}

private data class NotificationRow(
    val kind: String,
    val sourceId: UUID,
    val targetId: UUID,
    val deliveredAt: OffsetDateTime,
)
