package org.ergon.cases.domain

import java.time.Instant
import java.util.UUID

/** A durable fact in a case stream, timestamped when it occurred at the source. */
sealed interface CaseEvent {
    val occurredAt: Instant
}

/** Records the case goal and the requester observation that initiated it. */
data class CaseOpened(
    val goal: String,
    val observationId: UUID,
    val observationOriginType: ObservationOriginType,
    val observationProvider: String,
    val observationReference: String?,
    val observationContent: String,
    override val occurredAt: Instant,
) : CaseEvent

/** Records a source observation without assigning semantic meaning to its content. */
data class ObservationRecorded(
    val observationId: UUID,
    val observationOriginType: ObservationOriginType,
    val observationProvider: String,
    val observationReference: String?,
    val observationContent: String,
    override val occurredAt: Instant,
) : CaseEvent
