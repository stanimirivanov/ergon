package org.ergon.resolution.domain

import java.time.Instant

/** Run termination before authorization, never a claim that an external operation was stopped. */
enum class ResolutionRunCancellationReason {
    OPERATOR_REQUESTED,
}

/**
 * First and final transition of an untouched run. Authorization-history absence
 * and human authority are transactional application preconditions, not assertions
 * this value can establish. [fromState] must be an initial requirement state.
 * @throws IllegalArgumentException for a non-initial source state.
 */
data class ResolutionRunCancelled(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val fromState: ResolutionRunState,
    val occurredAt: Instant,
) {
    init {
        require(
            fromState == ResolutionRunState.WAITING_FOR_APPROVAL ||
                fromState == ResolutionRunState.READY_FOR_AUTHORIZATION,
        ) { "only untouched runs can be cancelled" }
    }

    val sequence: Long = 1
    val type: ResolutionRunEventType = ResolutionRunEventType.RUN_CANCELLED
    val toState: ResolutionRunState = ResolutionRunState.CANCELLED
    val reason: ResolutionRunCancellationReason = ResolutionRunCancellationReason.OPERATOR_REQUESTED
}
