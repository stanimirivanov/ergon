package org.ergon.resolution.domain

import org.ergon.identity.domain.HumanActorId
import java.time.Instant

/** Resolver identity and current attestation that authorized terminal human follow-up. */
data class ResolutionRunEscalationAuthorization(
    val actorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
) {
    companion object {
        /**
         * Derives escalation authority from a tenant-wide resolver attestation.
         *
         * Authentication and current-time evaluation remain adapter and application
         * responsibilities; this factory protects the durable authority shape.
         *
         * @throws IllegalArgumentException when [evidence] is not tenant-wide resolver evidence.
         */
        fun from(evidence: ApprovalAuthorityEvidence): ResolutionRunEscalationAuthorization {
            require(evidence.authority == ApprovalAuthority.RESOLVER && evidence.caseId == null) {
                "resolution run escalation requires tenant-wide resolver authority"
            }
            return ResolutionRunEscalationAuthorization(evidence.actorId, evidence.id)
        }
    }
}

/** Stable reason that automated recovery ended in explicit human follow-up. */
enum class ResolutionRunEscalationReason {
    RETRY_ATTEMPT_LIMIT_REACHED,
}

/** Failed run, state, authority, and exhausted retry decision used by one escalation. */
data class ResolutionRunEscalationBasis(
    val run: ResolutionRunStart,
    val currentState: ResolutionRunStateSnapshot,
    val authorityEvidence: ApprovalAuthorityEvidence,
    val retryDenial: ResolutionRetryEligibility.Denied,
)

/** Complete immutable values needed to rehydrate a validated escalation event. */
data class ResolutionRunEscalationSnapshot(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val sequence: Long,
    val type: ResolutionRunEventType,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val reason: ResolutionRunEscalationReason,
    val authorization: ResolutionRunEscalationAuthorization,
    val retryDenial: ResolutionRetryEligibility.Denied,
    val occurredAt: Instant,
)

/**
 * Immutable terminal handoff from exhausted automated recovery to human follow-up.
 *
 * Escalation does not close the case, assign a resolver, send a notification, or
 * authorize another capability. Those effects require separate durable decisions.
 */
@ConsistentCopyVisibility
data class ResolutionRunEscalationRequested private constructor(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val sequence: Long,
    val type: ResolutionRunEventType,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val reason: ResolutionRunEscalationReason,
    val authorization: ResolutionRunEscalationAuthorization,
    val retryDenial: ResolutionRetryEligibility.Denied,
    val occurredAt: Instant,
) {
    companion object {
        /**
         * Terminates an exhausted sequence-one failed attempt as escalated.
         *
         * @throws IllegalArgumentException when state, attempt, denial, authority,
         *   or time does not describe the same exhausted failed run.
         */
        fun request(
            id: ResolutionRunEventId,
            basis: ResolutionRunEscalationBasis,
            occurredAt: Instant,
        ): ResolutionRunEscalationRequested {
            require(basis.currentState.runId == basis.run.id) {
                "resolution run state belongs to another run"
            }
            require(
                basis.currentState.version == 1L &&
                    basis.currentState.state == ResolutionRunState.ACTION_FAILED,
            ) {
                "escalation requires an action-failed run at version one"
            }
            require(basis.retryDenial.sourceAttemptNumber == basis.run.attemptNumber) {
                "retry denial belongs to another source attempt"
            }
            require(basis.retryDenial.reason == ResolutionRetryDenialReason.ATTEMPT_LIMIT_REACHED) {
                "escalation requires an exhausted retry attempt limit"
            }
            require(!occurredAt.isBefore(basis.currentState.updatedAt)) {
                "escalation predates the failed run state"
            }
            return ResolutionRunEscalationRequested(
                id = id,
                runId = basis.run.id,
                sequence = 2,
                type = ResolutionRunEventType.ESCALATION_REQUESTED,
                fromState = ResolutionRunState.ACTION_FAILED,
                toState = ResolutionRunState.ESCALATED,
                reason = ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED,
                authorization = ResolutionRunEscalationAuthorization.from(basis.authorityEvidence),
                retryDenial = basis.retryDenial,
                occurredAt = occurredAt,
            )
        }

        /**
         * Reconstructs an escalation event read from constrained durable storage.
         *
         * @throws IllegalArgumentException when event type, sequence, state, reason,
         *   or retry denial does not describe exhausted human follow-up.
         */
        fun rehydrate(snapshot: ResolutionRunEscalationSnapshot): ResolutionRunEscalationRequested {
            require(snapshot.type == ResolutionRunEventType.ESCALATION_REQUESTED) {
                "escalation event type must be escalation requested"
            }
            require(snapshot.sequence == 2L) { "escalation must be the second run event" }
            require(snapshot.fromState == ResolutionRunState.ACTION_FAILED) {
                "escalation must start from action failed"
            }
            require(snapshot.toState == ResolutionRunState.ESCALATED) {
                "escalation must terminate the run as escalated"
            }
            require(snapshot.reason == ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED) {
                "escalation reason must identify retry attempt exhaustion"
            }
            require(snapshot.retryDenial.reason == ResolutionRetryDenialReason.ATTEMPT_LIMIT_REACHED) {
                "escalation retry denial must identify attempt limit exhaustion"
            }
            return ResolutionRunEscalationRequested(
                snapshot.id,
                snapshot.runId,
                snapshot.sequence,
                snapshot.type,
                snapshot.fromState,
                snapshot.toState,
                snapshot.reason,
                snapshot.authorization,
                snapshot.retryDenial,
                snapshot.occurredAt,
            )
        }
    }
}
