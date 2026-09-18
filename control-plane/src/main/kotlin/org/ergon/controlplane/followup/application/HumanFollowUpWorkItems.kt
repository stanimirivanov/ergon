package org.ergon.controlplane.followup.application

import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunEventId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Human follow-up work paired with the database instant at which it became durable. */
data class StoredHumanFollowUpWorkItem(
    val item: HumanFollowUpWorkItem,
    val recordedAt: Instant,
)

/** Stable keyset position in the oldest-first human follow-up inbox. */
data class HumanFollowUpWorkItemCursor(
    val openedAt: Instant,
    val workItemId: HumanFollowUpWorkItemId,
)

/** A bounded page of human follow-up work and the position for its successor page. */
data class HumanFollowUpWorkItemPage(
    val items: List<StoredHumanFollowUpWorkItem>,
    val nextCursor: HumanFollowUpWorkItemCursor?,
)

/** Durable storage and resolver-scoped lookup for human follow-up work. */
interface HumanFollowUpWorkItemRepository {
    /** Stores [item] as the sole work item for its escalation source. */
    fun create(
        tenantId: TenantId,
        item: HumanFollowUpWorkItem,
    ): StoredHumanFollowUpWorkItem

    /** @return work created from [escalationEventId], or `null` when none exists. */
    fun findByEscalation(
        tenantId: TenantId,
        escalationEventId: ResolutionRunEventId,
    ): StoredHumanFollowUpWorkItem?

    /**
     * Returns [workItemId] only when [actorId] has tenant-wide resolver evidence at [at].
     *
     * Absence and insufficient authority both return `null` so callers cannot use
     * this boundary to discover work outside their current authority.
     */
    fun findForResolver(
        tenantId: TenantId,
        workItemId: HumanFollowUpWorkItemId,
        actorId: HumanActorId,
        at: Instant,
    ): StoredHumanFollowUpWorkItem?

    /**
     * Lists `OPEN` work after [after] in ascending opening order.
     *
     * [limit] must be positive. Implementations must break equal opening times by
     * work-item identity and return no rows when [actorId] lacks current tenant-wide
     * resolver evidence at [at].
     */
    fun listOpenForResolver(
        tenantId: TenantId,
        actorId: HumanActorId,
        at: Instant,
        after: HumanFollowUpWorkItemCursor?,
        limit: Int,
    ): List<StoredHumanFollowUpWorkItem>
}

/** Supplies unpredictable identities without coupling follow-up use cases to UUID generation. */
fun interface HumanFollowUpWorkItemIdentityGenerator {
    /** @return a fresh identity suitable for one durable work item. */
    fun next(): HumanFollowUpWorkItemId
}

/** Hides tenant-scoped absence and missing resolver authority behind one result. */
class HumanFollowUpWorkItemNotFoundException(
    workItemId: UUID,
) : RuntimeException("human follow-up work item $workItemId was not found")

/** Signals malformed or out-of-range inbox pagination input. */
class InvalidHumanFollowUpWorkItemPageException(
    message: String,
) : RuntimeException(message)

/** Retrieves durable follow-up work for an authenticated, currently authorized resolver. */
class HumanFollowUpWorkItemQueryService(
    private val repository: HumanFollowUpWorkItemRepository,
    private val clock: Clock,
) {
    /**
     * Loads one work item without revealing cross-tenant or unauthorized existence.
     *
     * @throws HumanFollowUpWorkItemNotFoundException when the item is absent or
     *   [actorId] lacks current tenant-wide resolver evidence.
     */
    fun get(
        tenantId: UUID,
        workItemId: UUID,
        actorId: UUID,
    ): StoredHumanFollowUpWorkItem =
        repository.findForResolver(
            TenantId(tenantId),
            HumanFollowUpWorkItemId(workItemId),
            HumanActorId(actorId),
            clock.instant(),
        ) ?: throw HumanFollowUpWorkItemNotFoundException(workItemId)

    /**
     * Lists the oldest currently open work visible to one resolver.
     *
     * [afterOpenedAt] and [afterWorkItemId] must either both be absent for the
     * first page or both identify the final item returned by an earlier page.
     * Unauthorized callers receive an empty page so the query does not disclose
     * whether the tenant has follow-up work.
     *
     * @throws InvalidHumanFollowUpWorkItemPageException when [limit] is outside
     *   `1..100` or only one cursor component is supplied.
     */
    fun listOpen(
        tenantId: UUID,
        actorId: UUID,
        limit: Int,
        afterOpenedAt: Instant?,
        afterWorkItemId: UUID?,
    ): HumanFollowUpWorkItemPage {
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidHumanFollowUpWorkItemPageException("limit must be between 1 and $MAX_PAGE_SIZE")
        }
        if ((afterOpenedAt == null) != (afterWorkItemId == null)) {
            throw InvalidHumanFollowUpWorkItemPageException(
                "afterOpenedAt and afterWorkItemId must be supplied together",
            )
        }
        val cursor =
            afterOpenedAt?.let {
                HumanFollowUpWorkItemCursor(it, HumanFollowUpWorkItemId(requireNotNull(afterWorkItemId)))
            }
        val results =
            repository.listOpenForResolver(
                TenantId(tenantId),
                HumanActorId(actorId),
                clock.instant(),
                cursor,
                limit + 1,
            )
        val items = results.take(limit)
        val nextCursor =
            if (results.size > limit) {
                items.last().let { HumanFollowUpWorkItemCursor(it.item.openedAt, it.item.id) }
            } else {
                null
            }
        return HumanFollowUpWorkItemPage(items, nextCursor)
    }

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
