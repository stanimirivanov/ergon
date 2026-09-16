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
}
