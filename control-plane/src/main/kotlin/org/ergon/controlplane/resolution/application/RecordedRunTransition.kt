package org.ergon.controlplane.resolution.application

import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Instant

/**
 * Sanitized immutable transition metadata, without authority or provider coordinates.
 *
 * [sequence] is the authoritative append order, not an elapsed-time observation.
 * Occurrence and recording instants may move backwards after clock correction.
 * The current single-capability runtime permits at most two transitions per run;
 * this projection is not a live span or an outcome-proof evidence record.
 *
 * @throws IllegalArgumentException when sequence is outside 1..2 or the event
 *   type contradicts its sequence or source/destination states.
 */
data class RecordedRunTransition(
    val runId: ResolutionRunId,
    val sequence: Long,
    val type: ResolutionRunEventType,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val occurredAt: Instant,
    val recordedAt: Instant,
) {
    init {
        require(sequence in 1..2) { "recorded transition sequence must be in 1..2" }
        val validShape =
            when (type) {
                ResolutionRunEventType.RUN_CANCELLED -> {
                    sequence == 1L && isInitial(fromState) && toState == ResolutionRunState.CANCELLED
                }

                ResolutionRunEventType.CAPABILITY_SUCCEEDED -> {
                    sequence == 1L && isInitial(fromState) && toState == ResolutionRunState.VERIFYING
                }

                ResolutionRunEventType.CAPABILITY_FAILED -> {
                    sequence == 1L && isInitial(fromState) && toState == ResolutionRunState.ACTION_FAILED
                }

                ResolutionRunEventType.OUTCOME_PROOF_ACCEPTED -> {
                    sequence == 2L && fromState == ResolutionRunState.VERIFYING &&
                        toState == ResolutionRunState.VERIFIED_RESOLVED
                }

                ResolutionRunEventType.RETRY_STARTED -> {
                    sequence == 2L && fromState == ResolutionRunState.ACTION_FAILED &&
                        toState == ResolutionRunState.SUPERSEDED
                }

                ResolutionRunEventType.ESCALATION_REQUESTED -> {
                    sequence == 2L && fromState == ResolutionRunState.ACTION_FAILED &&
                        toState == ResolutionRunState.ESCALATED
                }
            }
        require(validShape) { "recorded transition shape is invalid" }
    }
}

/** Reject a missing, discontinuous or foreign prefix rather than returning mixed snapshot facts. */
internal fun checkRecordedRunTransitions(
    start: StoredResolutionRunStart,
    state: ResolutionRunStateSnapshot,
    events: List<RecordedRunTransition>,
) {
    check(state.version in 0..2 && events.size.toLong() == state.version) {
        "assigned run transition prefix is incomplete"
    }
    var previous = ResolutionRunState.valueOf(start.run.initialState.name)
    events.forEachIndexed { index, event ->
        check(event.runId == start.run.id && event.sequence == index + 1L && event.fromState == previous) {
            "assigned run transition prefix is inconsistent"
        }
        previous = event.toState
    }
    check(previous == state.state && (events.isEmpty() || events.last().occurredAt == state.updatedAt)) {
        "assigned run transition prefix disagrees with projected state"
    }
}

private fun isInitial(state: ResolutionRunState): Boolean =
    state == ResolutionRunState.WAITING_FOR_APPROVAL || state == ResolutionRunState.READY_FOR_AUTHORIZATION
