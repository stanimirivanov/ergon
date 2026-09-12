package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.TenantId
import java.time.Instant
import java.util.UUID

/** Durable, tenant-scoped storage for ordered [CaseEvent] streams. */
interface CaseEventStore {
    /** Returns the complete stream in version order, or an empty list when the tenant-scoped case does not exist. */
    fun load(
        tenantId: TenantId,
        caseId: CaseId,
    ): List<CaseEvent>

    /**
     * Atomically appends non-empty [events] after verifying [expectedVersion].
     *
     * @throws ConcurrentCaseModificationException when the durable stream has advanced.
     * @throws IllegalArgumentException when [events] is empty.
     */
    fun append(
        tenantId: TenantId,
        caseId: CaseId,
        expectedVersion: Long,
        events: List<NewCaseEvent>,
    ): List<StoredCaseEvent>
}

/** Event plus its application-generated durable identity. */
data class NewCaseEvent(
    val eventId: UUID,
    val event: CaseEvent,
)

/** Persisted event metadata returned by [CaseEventStore.append]. */
data class StoredCaseEvent(
    val eventId: UUID,
    val streamVersion: Long,
    val event: CaseEvent,
    val recordedAt: Instant,
)

/** Synchronously advances case read models from events already appended in the same transaction. */
interface CaseProjectionWriter {
    fun project(
        case: ErgonCase,
        events: List<StoredCaseEvent>,
    )
}

/** Query port for a tenant-scoped case timeline. */
interface CaseTimelineRepository {
    /** Returns `null` when [caseId] does not belong to [tenantId] or does not exist. */
    fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseTimeline?
}

/** Supplies unpredictable identities without coupling use cases to a UUID implementation. */
fun interface IdentityGenerator {
    fun next(): UUID
}

/** Executes a short local database unit of work and returns only after commit. */
interface TransactionRunner {
    fun <T : Any> required(block: () -> T): T
}
