package org.ergon.resolution.domain

import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable event in a resolution run. */
@JvmInline
value class ResolutionRunEventId(
    val value: UUID,
)

/** Closed set of current run states supported by the first execution slice. */
enum class ResolutionRunState {
    WAITING_FOR_APPROVAL,
    READY_FOR_AUTHORIZATION,
    VERIFYING,
    ACTION_FAILED,
    VERIFIED_RESOLVED,
    SUPERSEDED,
}

/** Closed set of append-only run events supported by the first execution slice. */
enum class ResolutionRunEventType {
    CAPABILITY_SUCCEEDED,
    CAPABILITY_FAILED,
    OUTCOME_PROOF_ACCEPTED,
    RETRY_STARTED,
}

/** Current projected run state used as the optimistic append precondition. */
data class ResolutionRunStateSnapshot(
    val runId: ResolutionRunId,
    val state: ResolutionRunState,
    val version: Long,
    val updatedAt: Instant,
) {
    init {
        require(version >= 0) { "resolution run state version must not be negative" }
    }
}

/** Inputs that must agree before a connector receipt can advance a run. */
data class ResolutionRunCapabilityResultBasis(
    val run: ResolutionRunStart,
    val currentState: ResolutionRunStateSnapshot,
    val receipt: CapabilityInvocationReceipt,
)

/** Complete immutable values needed to rehydrate a validated run event. */
data class ResolutionRunCapabilityResultSnapshot(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val sequence: Long,
    val type: ResolutionRunEventType,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val authorizationConsumptionId: CapabilityAuthorizationConsumptionId,
    val receiptOutcome: CapabilityInvocationOutcome,
    val occurredAt: Instant,
)

/**
 * Immutable run event derived from one terminal capability receipt.
 *
 * A successful connector result moves the run to [ResolutionRunState.VERIFYING],
 * never to a resolved state. Independent outcome evidence remains mandatory.
 * A failed result moves it to [ResolutionRunState.ACTION_FAILED] for later retry,
 * compensation, or human intervention policy.
 */
@ConsistentCopyVisibility
data class ResolutionRunCapabilityResult private constructor(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val sequence: Long,
    val type: ResolutionRunEventType,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val authorizationConsumptionId: CapabilityAuthorizationConsumptionId,
    val receiptOutcome: CapabilityInvocationOutcome,
    val occurredAt: Instant,
) {
    companion object {
        /**
         * Advances the exact run named by a terminal connector receipt.
         *
         * This first transition must start at version zero in the run's initial
         * requirement state. The receipt must preserve the run's case, policy,
         * step, and capability scope.
         *
         * @throws IllegalArgumentException when state, version, or receipt scope
         *   does not match the immutable run start.
         */
        fun record(
            id: ResolutionRunEventId,
            basis: ResolutionRunCapabilityResultBasis,
        ): ResolutionRunCapabilityResult {
            val (run, currentState, receipt) = basis
            require(currentState.runId == run.id) { "resolution run state belongs to another run" }
            require(currentState.version == 0L) { "capability result must be the first run event" }
            require(currentState.state == run.initialState.toCurrentState()) {
                "resolution run state does not match its immutable start"
            }
            require(receipt.runId == run.id) { "capability receipt belongs to another run" }
            require(receipt.caseId == run.caseId) { "capability receipt belongs to another case" }
            require(receipt.policyRevision == run.policyRevision) { "capability receipt uses another policy revision" }
            require(receipt.stepId == run.stepId) { "capability receipt belongs to another run step" }
            require(receipt.capability == run.capability) { "capability receipt reports another capability" }
            val transition = receipt.outcome.toTransition()
            return ResolutionRunCapabilityResult(
                id = id,
                runId = run.id,
                sequence = 1,
                type = transition.type,
                fromState = currentState.state,
                toState = transition.state,
                authorizationConsumptionId = receipt.authorizationConsumptionId,
                receiptOutcome = receipt.outcome,
                occurredAt = receipt.completedAt,
            )
        }

        /**
         * Reconstructs a run event read from constrained durable storage.
         *
         * @throws IllegalArgumentException when sequence, event type, outcome,
         *   or resulting state violates the capability-result mapping.
         */
        fun rehydrate(snapshot: ResolutionRunCapabilityResultSnapshot): ResolutionRunCapabilityResult {
            require(snapshot.sequence > 0) { "resolution run event sequence must be positive" }
            val transition = snapshot.receiptOutcome.toTransition()
            require(snapshot.type == transition.type) { "resolution run event type contradicts receipt outcome" }
            require(snapshot.toState == transition.state) { "resolution run state contradicts receipt outcome" }
            return ResolutionRunCapabilityResult(
                id = snapshot.id,
                runId = snapshot.runId,
                sequence = snapshot.sequence,
                type = snapshot.type,
                fromState = snapshot.fromState,
                toState = snapshot.toState,
                authorizationConsumptionId = snapshot.authorizationConsumptionId,
                receiptOutcome = snapshot.receiptOutcome,
                occurredAt = snapshot.occurredAt,
            )
        }
    }
}

/** Maps an immutable run start requirement into the initial current-state projection. */
fun ResolutionRunInitialState.toCurrentState(): ResolutionRunState = ResolutionRunState.valueOf(name)

private data class CapabilityResultTransition(
    val type: ResolutionRunEventType,
    val state: ResolutionRunState,
)

private fun CapabilityInvocationOutcome.toTransition(): CapabilityResultTransition =
    when (this) {
        CapabilityInvocationOutcome.SUCCEEDED -> {
            CapabilityResultTransition(ResolutionRunEventType.CAPABILITY_SUCCEEDED, ResolutionRunState.VERIFYING)
        }

        CapabilityInvocationOutcome.FAILED -> {
            CapabilityResultTransition(ResolutionRunEventType.CAPABILITY_FAILED, ResolutionRunState.ACTION_FAILED)
        }
    }
