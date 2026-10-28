package org.ergon.controlplane.followup.application

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal const val HUMAN_NOTIFICATIONS_LIMIT = 50

/** Selected by the trusted application boundary, never by recipient-supplied filtering. */
enum class HumanNotificationAudience { REQUESTER, RESOLVER }

/** Navigation coordinates only; opening either target requires an independent fresh access check. */
enum class HumanNotificationTargetKind { REQUESTER_CASE, OWNED_HUMAN_FOLLOW_UP }

enum class HumanNotificationKind(
    val targetKind: HumanNotificationTargetKind,
) {
    INFORMATION_REQUESTED(HumanNotificationTargetKind.REQUESTER_CASE),
    INFORMATION_ANSWERED(HumanNotificationTargetKind.OWNED_HUMAN_FOLLOW_UP),
    FOLLOW_UP_HANDED_OVER(HumanNotificationTargetKind.OWNED_HUMAN_FOLLOW_UP),
}

/**
 * Historical delivery without source text, actor attribution or continuing access.
 * Identity is (kind, sourceId, targetId): handover command UUIDs are work-scoped.
 * [deliveredAt] is database insertion time, not occurrence, observation or read acknowledgement.
 */
data class HumanNotification(
    val kind: HumanNotificationKind,
    val sourceId: UUID,
    val targetId: UUID,
    val deliveredAt: Instant,
)

/** At most 50 recent deliveries; [truncated] indicates older omitted entries, never an unread count. */
data class HumanNotificationsPage(
    val items: List<HumanNotification>,
    val truncated: Boolean,
)

fun interface HumanNotificationReader {
    /**
     * One statement snapshot, scoped to exact tenant/recipient and trusted audience.
     * Return at most [limit] rows (1..51), ordered descending by delivery time, kind (C), source and target UUID.
     * Do not filter by current target ownership or mutate delivery. SQL failures propagate.
     */
    fun latest(
        tenant: TenantId,
        recipient: HumanActorId,
        audience: HumanNotificationAudience,
        limit: Int,
    ): List<HumanNotification>
}

class HumanNotificationsForbiddenException : RuntimeException("notification feed is unavailable")

/** Bounded historical listing; no target locks, access grants, acknowledgement or automatic retries. */
class HumanNotificationService(
    private val reader: HumanNotificationReader,
    private val authorities: HumanAuthorityRepository,
    private val clock: Clock,
) {
    /**
     * [actorId] must come from a registered verified session; [audience] is server-selected.
     * Requesters need no approval authority. Resolver authority is checked before and after the read,
     * including after database waits; target APIs independently establish current access on navigation.
     * @throws HumanNotificationsForbiddenException if fresh unscoped resolver authority is absent.
     */
    fun list(
        tenantId: UUID,
        actorId: UUID,
        audience: HumanNotificationAudience,
    ): HumanNotificationsPage {
        val tenant = TenantId(tenantId)
        val actor = HumanActorId(actorId)
        requireAuthority(tenant, actor, audience)
        val rows = reader.latest(tenant, actor, audience, HUMAN_NOTIFICATIONS_LIMIT + 1)
        requireAuthority(tenant, actor, audience)
        return HumanNotificationsPage(rows.take(HUMAN_NOTIFICATIONS_LIMIT), rows.size > HUMAN_NOTIFICATIONS_LIMIT)
    }

    private fun requireAuthority(
        tenant: TenantId,
        actor: HumanActorId,
        audience: HumanNotificationAudience,
    ) {
        if (audience == HumanNotificationAudience.REQUESTER) return
        val stored =
            authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
                ?: throw HumanNotificationsForbiddenException()
        // The lookup itself may wait for a connection/query; recheck the immutable interval after it returns.
        val now = clock.instant()
        if (now.isBefore(stored.evidence.attestedAt) ||
            stored.evidence.statusAt(now) != ApprovalAuthorityEvidenceStatus.CURRENT
        ) {
            throw HumanNotificationsForbiddenException()
        }
    }
}
