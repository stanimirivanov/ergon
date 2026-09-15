package org.ergon.resolution.domain

import org.ergon.identity.domain.HumanActorId
import java.time.Instant

/** Resolver identity and current attestation that authorized an explicit retry. */
data class ResolutionRunRetryAuthorization(
    val actorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
) {
    companion object {
        /**
         * Derives retry authority from a tenant-wide resolver attestation.
         *
         * Authentication and current-time evaluation remain adapter and application
         * responsibilities; this factory only protects the durable authority shape.
         *
         * @throws IllegalArgumentException when [evidence] is not tenant-wide resolver evidence.
         */
        fun from(evidence: ApprovalAuthorityEvidence): ResolutionRunRetryAuthorization {
            require(evidence.authority == ApprovalAuthority.RESOLVER && evidence.caseId == null) {
                "resolution run retry requires tenant-wide resolver authority"
            }
            return ResolutionRunRetryAuthorization(evidence.actorId, evidence.id)
        }
    }
}

/** Failed attempt, successor, state, and resolver evidence considered by one retry decision. */
data class ResolutionRunRetryBasis(
    val failedRun: ResolutionRunStart,
    val currentState: ResolutionRunStateSnapshot,
    val replacementRun: ResolutionRunStart,
    val authorityEvidence: ApprovalAuthorityEvidence,
)

/** Complete immutable values needed to rehydrate a validated retry event. */
data class ResolutionRunRetrySnapshot(
    val id: ResolutionRunEventId,
    val failedRunId: ResolutionRunId,
    val replacementRunId: ResolutionRunId,
    val sequence: Long,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val authorization: ResolutionRunRetryAuthorization?,
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
    /** `null` only for retry events recorded before resolver attribution was introduced. */
    val authorization: ResolutionRunRetryAuthorization?,
    val occurredAt: Instant,
) {
    companion object {
        /**
         * Supersedes the failed run in [basis] with its directly succeeding replacement.
         *
         * @throws IllegalArgumentException when the current state is not the
         *   failed run's sequence-one `ACTION_FAILED` state, or the replacement
         *   does not directly identify that run as its predecessor.
         */
        fun start(
            id: ResolutionRunEventId,
            basis: ResolutionRunRetryBasis,
            occurredAt: Instant,
        ): ResolutionRunRetryStarted {
            val failedRun = basis.failedRun
            val currentState = basis.currentState
            val replacementRun = basis.replacementRun
            val authorityEvidence = basis.authorityEvidence
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
                authorization = ResolutionRunRetryAuthorization.from(authorityEvidence),
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
                snapshot.authorization,
                snapshot.occurredAt,
            )
        }
    }
}
