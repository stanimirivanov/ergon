package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.TenantId
import java.time.Instant
import java.util.UUID

interface CaseEventStore {
    fun load(
        tenantId: TenantId,
        caseId: CaseId,
    ): List<CaseEvent>

    fun append(
        tenantId: TenantId,
        caseId: CaseId,
        expectedVersion: Long,
        events: List<NewCaseEvent>,
    ): List<StoredCaseEvent>
}

data class NewCaseEvent(
    val eventId: UUID,
    val event: CaseEvent,
)

data class StoredCaseEvent(
    val eventId: UUID,
    val streamVersion: Long,
    val event: CaseEvent,
    val recordedAt: Instant,
)

interface CaseProjectionWriter {
    fun project(
        case: ErgonCase,
        events: List<StoredCaseEvent>,
    )
}

interface CaseTimelineRepository {
    fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseTimeline?
}

fun interface IdentityGenerator {
    fun next(): UUID
}

interface TransactionRunner {
    fun <T : Any> required(block: () -> T): T
}
