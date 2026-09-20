package org.ergon.controlplane.followup.application

import org.ergon.followup.domain.HumanFollowUpQueueKey
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

/** Validated resolver scope and page position for one shared-inbox read. */
data class HumanFollowUpInboxCriteria(
    val tenantId: TenantId,
    val actorId: HumanActorId,
    val at: Instant,
    val queueKey: HumanFollowUpQueueKey?,
    val after: HumanFollowUpWorkItemCursor?,
    val limit: Int,
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
     * Lists unclaimed `OPEN` work after the criteria cursor in ascending opening order.
     *
     * When [HumanFollowUpInboxCriteria.queueKey] is present, only work routed
     * to that immutable queue is returned. The limit must be positive.
     * Implementations must break equal opening times by work-item identity and
     * return no rows when the actor lacks current tenant-wide resolver evidence
     * at the query instant.
     */
    fun listOpenForResolver(criteria: HumanFollowUpInboxCriteria): List<StoredHumanFollowUpWorkItem>
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

/** Signals a shared-inbox queue key that cannot identify a durable queue. */
class InvalidHumanFollowUpQueueException(
    cause: IllegalArgumentException,
) : RuntimeException(
        "queueKey must start with a lowercase letter and contain at most 63 lowercase letters, digits, or hyphens",
        cause,
    )

/** Untrusted shared-inbox parameters before application validation and domain conversion. */
data class HumanFollowUpInboxQuery(
    val tenantId: UUID,
    val actorId: UUID,
    val queueKey: String?,
    val limit: Int,
    val afterOpenedAt: Instant?,
    val afterWorkItemId: UUID?,
)

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
     * Lists the oldest currently open and unclaimed work visible to one resolver.
     *
     * [HumanFollowUpInboxQuery.queueKey], when present, must be a lowercase
     * URL-safe durable queue key. The cursor components must either both be absent for the
     * first page or both identify the final item returned by an earlier page.
     * Clients must retain the same queue filter while following a cursor.
     * Unauthorized callers receive an empty page so the query does not disclose
     * whether the tenant has follow-up work.
     *
     * @throws InvalidHumanFollowUpWorkItemPageException when the limit is outside
     *   `1..100` or only one cursor component is supplied.
     * @throws InvalidHumanFollowUpQueueException when the queue key is malformed.
     */
    fun listOpen(query: HumanFollowUpInboxQuery): HumanFollowUpWorkItemPage {
        validateLimit(query.limit)
        validateCursor(query.afterOpenedAt, query.afterWorkItemId)
        val parsedQueueKey = query.queueKey?.let(::parseQueueKey)
        val cursor =
            query.afterOpenedAt?.let {
                HumanFollowUpWorkItemCursor(it, HumanFollowUpWorkItemId(requireNotNull(query.afterWorkItemId)))
            }
        val results =
            repository.listOpenForResolver(
                HumanFollowUpInboxCriteria(
                    TenantId(query.tenantId),
                    HumanActorId(query.actorId),
                    clock.instant(),
                    parsedQueueKey,
                    cursor,
                    query.limit + 1,
                ),
            )
        val items = results.take(query.limit)
        val nextCursor =
            if (results.size > query.limit) {
                items.last().let { HumanFollowUpWorkItemCursor(it.item.openedAt, it.item.id) }
            } else {
                null
            }
        return HumanFollowUpWorkItemPage(items, nextCursor)
    }

    private fun validateLimit(limit: Int) {
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw InvalidHumanFollowUpWorkItemPageException("limit must be between 1 and $MAX_PAGE_SIZE")
        }
    }

    private fun validateCursor(
        afterOpenedAt: Instant?,
        afterWorkItemId: UUID?,
    ) {
        if ((afterOpenedAt == null) != (afterWorkItemId == null)) {
            throw InvalidHumanFollowUpWorkItemPageException(
                "afterOpenedAt and afterWorkItemId must be supplied together",
            )
        }
    }

    private fun parseQueueKey(queueKey: String): HumanFollowUpQueueKey =
        try {
            HumanFollowUpQueueKey.of(queueKey)
        } catch (exception: IllegalArgumentException) {
            throw InvalidHumanFollowUpQueueException(exception)
        }

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
