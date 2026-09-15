package org.ergon.resolution.domain

import java.time.Instant

/** Complete immutable values needed to rehydrate a validated retry event. */
data class ResolutionRunRetrySnapshot(
    val id: ResolutionRunEventId,
    val failedRunId: ResolutionRunId,
    val replacementRunId: ResolutionRunId,
    val sequence: Long,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val occurredAt: Instant,
)

/**
 * Immutable link from one failed attempt to its explicitly started successor.
 *
 * This event only supersedes the failed attempt. The successor starts from its
 * own policy-derived requirement state and must obtain fresh authorization
 * before any connector is invoked.
 */
@ConsistentCopyVisibility
data class ResolutionRunRetryStarted private constructor(
    val id: ResolutionRunEventId,
    val failedRunId: ResolutionRunId,
    val replacementRunId: ResolutionRunId,
    val sequence: Long,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val occurredAt: Instant,
) {
    companion object {
        /**
         * Supersedes [failedRun] with the directly succeeding [replacementRun].
         *
         * @throws IllegalArgumentException when the current state is not the
         *   failed run's sequence-one `ACTION_FAILED` state, or the replacement
         *   does not directly identify that run as its predecessor.
         */
        fun start(
            id: ResolutionRunEventId,
            failedRun: ResolutionRunStart,
            currentState: ResolutionRunStateSnapshot,
            replacementRun: ResolutionRunStart,
            occurredAt: Instant,
        ): ResolutionRunRetryStarted {
            require(currentState.runId == failedRun.id) { "resolution run state belongs to another run" }
            require(currentState.version == 1L && currentState.state == ResolutionRunState.ACTION_FAILED) {
                "retry requires an action-failed run at version one"
            }
            require(replacementRun.predecessorRunId == failedRun.id) {
                "replacement run does not identify the failed predecessor"
            }
            require(replacementRun.attemptNumber == failedRun.attemptNumber + 1) {
                "replacement run must be the next attempt"
            }
            require(!occurredAt.isBefore(currentState.updatedAt)) {
                "retry start predates the failed run state"
            }
            return ResolutionRunRetryStarted(
                id,
                failedRun.id,
                replacementRun.id,
                sequence = 2,
                fromState = ResolutionRunState.ACTION_FAILED,
                toState = ResolutionRunState.SUPERSEDED,
                occurredAt,
            )
        }

        /**
         * Reconstructs a retry event read from constrained durable storage.
         *
         * @throws IllegalArgumentException when its sequence or state transition
         *   does not describe a failed run superseded by a distinct successor.
         */
        fun rehydrate(snapshot: ResolutionRunRetrySnapshot): ResolutionRunRetryStarted {
            require(snapshot.sequence == 2L) { "retry must be the second run event" }
            require(snapshot.fromState == ResolutionRunState.ACTION_FAILED) {
                "retry must start from action failed"
            }
            require(snapshot.toState == ResolutionRunState.SUPERSEDED) {
                "retry must supersede the failed run"
            }
            require(snapshot.failedRunId != snapshot.replacementRunId) {
                "retry replacement must be a distinct run"
            }
            return ResolutionRunRetryStarted(
                snapshot.id,
                snapshot.failedRunId,
                snapshot.replacementRunId,
                snapshot.sequence,
                snapshot.fromState,
                snapshot.toState,
                snapshot.occurredAt,
            )
        }
    }
}
