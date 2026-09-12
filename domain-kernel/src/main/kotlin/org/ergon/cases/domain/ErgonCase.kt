package org.ergon.cases.domain

/**
 * Owns the ordered case event stream and enforces case-level write invariants.
 *
 * Persistence is supplied by the application layer; this aggregate has no infrastructure concerns.
 */
class ErgonCase private constructor(
    val id: CaseId,
    val tenantId: TenantId,
) {
    lateinit var goal: CaseGoal
        private set

    var status: CaseStatus = CaseStatus.OPEN
        private set

    var streamVersion: Long = 0
        private set

    private val changes = mutableListOf<CaseEvent>()

    /** Records one validated source observation as the next uncommitted event. */
    fun record(observation: SourceObservation) {
        record(
            ObservationRecorded(
                observationId = observation.id.value,
                observationOriginType = observation.origin.type,
                observationProvider = observation.origin.provider,
                observationReference = observation.origin.reference,
                observationContent = observation.content,
                occurredAt = observation.observedAt,
            ),
        )
    }

    /** Returns events created since construction or the last [markChangesCommitted] call. */
    fun pendingEvents(): List<CaseEvent> = changes.toList()

    /** Clears pending events after their transaction has committed; aggregate state is preserved. */
    fun markChangesCommitted() = changes.clear()

    private fun record(event: CaseEvent) {
        apply(event)
        changes += event
    }

    private fun apply(event: CaseEvent) {
        when (event) {
            is CaseOpened -> {
                goal = CaseGoal.of(event.goal)
                status = CaseStatus.OPEN
            }

            is ObservationRecorded -> {
                // Observation details remain in the event stream and timeline projection.
            }
        }
        streamVersion++
    }

    companion object {
        /**
         * Opens a case from a requester observation at stream version one.
         *
         * @throws IllegalArgumentException when [initialObservation] is not requester-originated.
         */
        fun open(
            id: CaseId,
            tenantId: TenantId,
            goal: CaseGoal,
            initialObservation: SourceObservation,
        ): ErgonCase {
            require(initialObservation.origin.type == ObservationOriginType.REQUESTER) {
                "case must open from a requester observation"
            }
            return ErgonCase(id, tenantId).also {
                it.record(
                    CaseOpened(
                        goal = goal.value,
                        observationId = initialObservation.id.value,
                        observationOriginType = initialObservation.origin.type,
                        observationProvider = initialObservation.origin.provider,
                        observationReference = initialObservation.origin.reference,
                        observationContent = initialObservation.content,
                        occurredAt = initialObservation.observedAt,
                    ),
                )
            }
        }

        /**
         * Restores an aggregate from a complete, ordered history without creating new events.
         *
         * @throws IllegalArgumentException when history is empty or does not start with [CaseOpened].
         */
        fun rehydrate(
            id: CaseId,
            tenantId: TenantId,
            history: List<CaseEvent>,
        ): ErgonCase {
            require(history.isNotEmpty()) { "case history must not be empty" }
            require(history.first() is CaseOpened) { "case history must begin with CaseOpened" }
            return ErgonCase(id, tenantId).also { case -> history.forEach(case::apply) }
        }
    }
}
