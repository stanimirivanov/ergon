package org.ergon.cases.domain

import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.identity.domain.TenantId
import java.time.Instant

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

    /** Exact instructions selected for this case, or `null` until planning pins them. */
    var pinnedResolutionContract: ResolutionContractIdentity? = null
        private set

    private val changes = mutableListOf<CaseEvent>()
    private val observations = mutableMapOf<ObservationId, ObservationOrigin>()
    private val accountAccessFactObservations = mutableSetOf<ObservationId>()

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
     * Attributes [state] to an account identified by an existing connector
     * observation.
     *
     * The account reference is inherited from the observation rather than
     * accepted separately, preventing a binder from attaching source material
     * to a different account. One observation may establish this property only
     * once; a changed state requires a new observation.
     *
     * @throws IllegalArgumentException if the observation is absent, is not
     *   connector-authored, or already has an account-access fact.
     */
    fun bindAccountAccessState(
        factId: FactId,
        observationId: ObservationId,
        state: AccountAccessState,
        boundAt: Instant,
    ) {
        val origin =
            requireNotNull(observations[observationId]) {
                "observation ${observationId.value} does not belong to this case"
            }
        require(origin.type == ObservationOriginType.CONNECTOR) {
            "account access state requires a connector observation"
        }
        require(observationId !in accountAccessFactObservations) {
            "observation ${observationId.value} already has an account access state fact"
        }
        record(
            AccountAccessStateBound(
                factId = factId.value,
                observationId = observationId.value,
                accountReference = requireNotNull(origin.reference),
                state = state,
                occurredAt = boundAt,
            ),
        )
    }

    /**
     * Pins [contract] as the immutable instructions selected for this case.
     *
     * A case can have only one pin. Changing the chosen instructions must be
     * modeled later as an explicit replanning decision rather than rewriting
     * the history on which a resolution run depends.
     *
     * @throws IllegalArgumentException if a contract is already pinned.
     */
    fun pinResolutionContract(
        contract: ResolutionContractIdentity,
        pinnedAt: Instant,
    ) {
        require(pinnedResolutionContract == null) { "case already has a pinned resolution contract" }
        record(
            ResolutionContractRevisionPinned(
                contractKey = contract.key.value,
                contractRevision = contract.revision.value,
                occurredAt = pinnedAt,
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
                rememberObservation(event.observation())
            }

            is ObservationRecorded -> {
                rememberObservation(event.observation())
            }

            is AccountAccessStateBound -> {
                val observationId = ObservationId(event.observationId)
                val origin =
                    requireNotNull(observations[observationId]) {
                        "account access fact references an unknown observation"
                    }
                require(origin.type == ObservationOriginType.CONNECTOR) {
                    "account access fact requires a connector observation"
                }
                require(origin.reference == event.accountReference) {
                    "account access fact must inherit its observation account reference"
                }
                require(accountAccessFactObservations.add(observationId)) {
                    "observation already has an account access state fact"
                }
            }

            is ResolutionContractRevisionPinned -> {
                require(pinnedResolutionContract == null) {
                    "case already has a pinned resolution contract"
                }
                pinnedResolutionContract =
                    ResolutionContractIdentity(
                        key = ResolutionContractKey.of(event.contractKey),
                        revision = ResolutionContractRevision.of(event.contractRevision),
                    )
            }
        }
        streamVersion++
    }

    private fun rememberObservation(observation: SourceObservation) {
        require(observations.putIfAbsent(observation.id, observation.origin) == null) {
            "case observation identities must be unique"
        }
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

private fun CaseOpened.observation() =
    SourceObservation.create(
        id = ObservationId(observationId),
        origin = observationOrigin(observationOriginType, observationProvider, observationReference),
        content = observationContent,
        observedAt = occurredAt,
    )

private fun ObservationRecorded.observation() =
    SourceObservation.create(
        id = ObservationId(observationId),
        origin = observationOrigin(observationOriginType, observationProvider, observationReference),
        content = observationContent,
        observedAt = occurredAt,
    )

private fun observationOrigin(
    originType: ObservationOriginType,
    provider: String,
    reference: String?,
): ObservationOrigin =
    when (originType) {
        ObservationOriginType.REQUESTER -> {
            require(provider == "api" && reference == null) {
                "requester observation must use the canonical API origin"
            }
            ObservationOrigin.requesterApi()
        }

        ObservationOriginType.CONNECTOR -> {
            ObservationOrigin.connector(provider, requireNotNull(reference))
        }
    }
