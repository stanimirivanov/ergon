package org.ergon.cases.domain

/**
 * The case aggregate: owns the ordered event stream for one case and
 * enforces write-time invariants against it.
 *
 * Externally visible instances are valid: the only ways to obtain one are
 * [open] and [rehydrate], and every applied event advances [streamVersion] by
 * exactly one, so it always equals the count of events applied so far. This
 * class has no persistence concerns of its own: the caller loads history into
 * it and is responsible for durably saving [pendingEvents] after any call that
 * records new ones.
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

    /**
     * Records a new observation against this case.
     *
     * Appends an [ObservationRecorded] event to [pendingEvents] and advances
     * [streamVersion] by one. Does not change [status]—only opening a case
     * does that.
     */
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

    /**
     * @return a snapshot of events applied since construction or the last
     *   [markChangesCommitted], in recording order. Nothing clears this list
     *   automatically.
     */
    fun pendingEvents(): List<CaseEvent> = changes.toList()

    /**
     * Clears [pendingEvents] while preserving aggregate state.
     *
     * Call only after the transaction that persisted every pending event has
     * committed; clearing earlier would make a failed write unrecoverable from
     * this aggregate instance.
     */
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
         * Opens a new case at stream version one from a requester's initial
         * observation.
         *
         * @return an open aggregate with one pending [CaseOpened] event.
         * @throws IllegalArgumentException if [initialObservation]'s origin
         *   isn't [ObservationOriginType.REQUESTER]—a case cannot be opened
         *   from a connector observation.
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
         * Reconstructs a case by replaying its complete history in order.
         *
         * This is the only way to load an existing case for a command—there
         * is no snapshot path yet, so [history] is replayed from the start
         * every time. The caller must supply the complete stream in
         * oldest-first order; events do not carry versions with which to verify
         * ordering or completeness here.
         *
         * @return an aggregate whose stream version is `history.size` and that
         *   has no pending events.
         * @throws IllegalArgumentException if [history] is empty or doesn't
         *   begin with [CaseOpened].
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
