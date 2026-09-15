package org.ergon.resolution.domain

import org.ergon.identity.domain.HumanActorId
import java.time.Instant

private const val MAX_RETRY_POLICY_REVISION_LENGTH = 200
private const val RETRY_POLICY_REVISION_PATTERN = "[a-z][a-z0-9.-]*(/[a-z0-9][a-z0-9.-]*)+"

/** Immutable identity of the rules used to decide whether another attempt may start. */
@JvmInline
value class ResolutionRetryPolicyRevision private constructor(
    val value: String,
) {
    companion object {
        /**
         * Creates a revision identifier without normalizing it.
         *
         * @throws IllegalArgumentException when [value] exceeds 200 characters
         *   or is not a lowercase, path-like identifier.
         */
        fun of(value: String): ResolutionRetryPolicyRevision {
            require(
                value.length <= MAX_RETRY_POLICY_REVISION_LENGTH &&
                    value.matches(Regex(RETRY_POLICY_REVISION_PATTERN)),
            ) {
                "retry policy revision must be a path-like identifier of at most " +
                    "$MAX_RETRY_POLICY_REVISION_LENGTH characters"
            }
            return ResolutionRetryPolicyRevision(value)
        }
    }
}

/** Stable reason that a retry policy refuses to start another attempt. */
enum class ResolutionRetryDenialReason {
    ATTEMPT_LIMIT_REACHED,
}

/**
 * Auditable outcome of evaluating one failed attempt against an exact retry policy.
 *
 * [maximumAttempts] includes the initial attempt. [Eligible] permits only a
 * positive source below that ceiling; [Denied] describes a source at or above it.
 *
 * @throws IllegalArgumentException when a decision's source and ceiling do not
 *   describe its declared eligibility.
 */
sealed interface ResolutionRetryEligibility {
    val revision: ResolutionRetryPolicyRevision
    val sourceAttemptNumber: Int
    val maximumAttempts: Int

    data class Eligible(
        override val revision: ResolutionRetryPolicyRevision,
        override val sourceAttemptNumber: Int,
        override val maximumAttempts: Int,
    ) : ResolutionRetryEligibility {
        init {
            require(sourceAttemptNumber in 1 until maximumAttempts) {
                "eligible retry source must be below the maximum attempt count"
            }
        }
    }

    data class Denied(
        override val revision: ResolutionRetryPolicyRevision,
        override val sourceAttemptNumber: Int,
        override val maximumAttempts: Int,
        val reason: ResolutionRetryDenialReason,
    ) : ResolutionRetryEligibility {
        init {
            require(maximumAttempts > 0 && sourceAttemptNumber >= maximumAttempts) {
                "denied retry source must have reached the positive maximum attempt count"
            }
        }
    }
}

/** Deterministic upper bound on the total attempts in one linked run chain. */
class ResolutionRetryPolicy private constructor(
    val revision: ResolutionRetryPolicyRevision,
    val maximumAttempts: Int,
) {
    /**
     * Decides whether [sourceAttemptNumber] may have a direct successor.
     *
     * The maximum includes the initial attempt. This decision does not classify
     * failures, schedule work, grant authority, or invoke a capability.
     *
     * @throws IllegalArgumentException when [sourceAttemptNumber] is not positive.
     */
    fun evaluate(sourceAttemptNumber: Int): ResolutionRetryEligibility {
        require(sourceAttemptNumber > 0) { "source attempt number must be positive" }
        return if (sourceAttemptNumber < maximumAttempts) {
            ResolutionRetryEligibility.Eligible(revision, sourceAttemptNumber, maximumAttempts)
        } else {
            ResolutionRetryEligibility.Denied(
                revision,
                sourceAttemptNumber,
                maximumAttempts,
                ResolutionRetryDenialReason.ATTEMPT_LIMIT_REACHED,
            )
        }
    }

    companion object {
        /**
         * Defines a retry policy whose maximum includes the initial attempt.
         *
         * @throws IllegalArgumentException when [maximumAttempts] is not positive.
         */
        fun define(
            revision: ResolutionRetryPolicyRevision,
            maximumAttempts: Int,
        ): ResolutionRetryPolicy {
            require(maximumAttempts > 0) { "maximum attempts must be positive" }
            return ResolutionRetryPolicy(revision, maximumAttempts)
        }
    }
}

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

/** Failed attempt, successor, state, authority, and bounded eligibility used by one retry decision. */
data class ResolutionRunRetryBasis(
    val failedRun: ResolutionRunStart,
    val currentState: ResolutionRunStateSnapshot,
    val replacementRun: ResolutionRunStart,
    val authorityEvidence: ApprovalAuthorityEvidence,
    val eligibility: ResolutionRetryEligibility.Eligible,
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
    val eligibility: ResolutionRetryEligibility.Eligible?,
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
    /** `null` only for retry events recorded before bounded eligibility was introduced. */
    val eligibility: ResolutionRetryEligibility.Eligible?,
    val occurredAt: Instant,
) {
    companion object {
        /**
         * Supersedes the failed run in [basis] with its directly succeeding replacement.
         *
         * @throws IllegalArgumentException when the current state is not the
         *   failed run's sequence-one `ACTION_FAILED` state, or the replacement
         *   is not its direct next attempt, eligibility names another source,
         *   authority is not tenant-wide resolver evidence, or [occurredAt]
         *   predates the failed state.
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
            require(basis.eligibility.sourceAttemptNumber == failedRun.attemptNumber) {
                "retry eligibility belongs to another source attempt"
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
                eligibility = basis.eligibility,
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
                snapshot.eligibility,
                snapshot.occurredAt,
            )
        }
    }
}
