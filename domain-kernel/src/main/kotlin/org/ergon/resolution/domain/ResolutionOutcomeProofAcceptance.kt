package org.ergon.resolution.domain

import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import java.time.Instant

/** Inputs that must agree before accepted outcome proof can complete a run. */
data class ResolutionOutcomeProofAcceptanceBasis(
    val run: ResolutionRunStart,
    val currentState: ResolutionRunStateSnapshot,
    val assessment: ResolutionOutcomeProofAssessment.Accepted,
    val acceptedAt: Instant,
)

/** Complete immutable values needed to rehydrate an accepted-proof run event. */
data class ResolutionOutcomeProofAcceptedSnapshot(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val sequence: Long,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val runCaseStreamVersion: Long,
    val caseStreamVersion: Long,
    val fact: ContractFactType,
    val expectedValue: ContractFactValue,
    val factId: FactId,
    val observationId: ObservationId,
    val observationStreamVersion: Long,
    val factStreamVersion: Long,
    val acceptedAt: Instant,
)

/**
 * Immutable run event that freezes the exact evidence accepted as outcome proof.
 *
 * It is always sequence two, follows successful capability execution in
 * [ResolutionRunState.VERIFYING], and terminates the run in
 * [ResolutionRunState.VERIFIED_RESOLVED].
 */
@ConsistentCopyVisibility
data class ResolutionOutcomeProofAccepted private constructor(
    val id: ResolutionRunEventId,
    val runId: ResolutionRunId,
    val sequence: Long,
    val type: ResolutionRunEventType,
    val fromState: ResolutionRunState,
    val toState: ResolutionRunState,
    val runCaseStreamVersion: Long,
    val caseStreamVersion: Long,
    val fact: ContractFactType,
    val expectedValue: ContractFactValue,
    val factId: FactId,
    val observationId: ObservationId,
    val observationStreamVersion: Long,
    val factStreamVersion: Long,
    val acceptedAt: Instant,
) {
    companion object {
        /**
         * Freezes [basis.assessment] as the terminal transition for its run.
         *
         * @throws IllegalArgumentException when the state is not the first
         *   `VERIFYING` version, the assessment predates the run, or its
         *   evidence lies outside the assessed case version.
         */
        fun record(
            id: ResolutionRunEventId,
            basis: ResolutionOutcomeProofAcceptanceBasis,
        ): ResolutionOutcomeProofAccepted {
            val assessment = basis.assessment
            require(basis.currentState.runId == basis.run.id) {
                "resolution run state belongs to another run"
            }
            require(basis.currentState.state == ResolutionRunState.VERIFYING) {
                "outcome proof requires a verifying run"
            }
            require(basis.currentState.version == 1L) {
                "outcome proof must be the second run event"
            }
            require(assessment.caseStreamVersion > basis.run.caseStreamVersion) {
                "outcome proof must follow the run evidence boundary"
            }
            require(assessment.evidence.observationStreamVersion > basis.run.caseStreamVersion) {
                "proof observation must follow the run evidence boundary"
            }
            require(assessment.evidence.factStreamVersion > basis.run.caseStreamVersion) {
                "proof fact must follow the run evidence boundary"
            }
            require(assessment.evidence.factStreamVersion <= assessment.caseStreamVersion) {
                "outcome fact cannot follow the assessed case version"
            }
            require(basis.acceptedAt >= assessment.evidence.boundAt) {
                "outcome proof cannot be accepted before its fact binding"
            }
            return from(
                ResolutionOutcomeProofAcceptedSnapshot(
                    id = id,
                    runId = basis.run.id,
                    sequence = 2,
                    fromState = ResolutionRunState.VERIFYING,
                    toState = ResolutionRunState.VERIFIED_RESOLVED,
                    runCaseStreamVersion = basis.run.caseStreamVersion,
                    caseStreamVersion = assessment.caseStreamVersion,
                    fact = assessment.condition.fact,
                    expectedValue = assessment.condition.expectedValue,
                    factId = assessment.evidence.factId,
                    observationId = assessment.evidence.observationId,
                    observationStreamVersion = assessment.evidence.observationStreamVersion,
                    factStreamVersion = assessment.evidence.factStreamVersion,
                    acceptedAt = basis.acceptedAt,
                ),
            )
        }

        /**
         * Reconstructs an accepted-proof event from constrained durable storage.
         *
         * @throws IllegalArgumentException when its sequence, states, or
         *   evidence versions contradict the accepted-proof event shape.
         */
        fun rehydrate(snapshot: ResolutionOutcomeProofAcceptedSnapshot): ResolutionOutcomeProofAccepted = from(snapshot)

        private fun from(snapshot: ResolutionOutcomeProofAcceptedSnapshot): ResolutionOutcomeProofAccepted {
            require(snapshot.sequence == 2L) { "outcome proof must be the second run event" }
            require(snapshot.fromState == ResolutionRunState.VERIFYING) {
                "outcome proof must start from verifying"
            }
            require(snapshot.toState == ResolutionRunState.VERIFIED_RESOLVED) {
                "outcome proof must complete the run"
            }
            require(snapshot.observationStreamVersion > 0) {
                "proof observation stream version must be positive"
            }
            require(snapshot.observationStreamVersion > snapshot.runCaseStreamVersion) {
                "proof observation must follow the run evidence boundary"
            }
            require(snapshot.factStreamVersion > snapshot.observationStreamVersion) {
                "proof fact must follow its observation"
            }
            require(snapshot.factStreamVersion <= snapshot.caseStreamVersion) {
                "proof fact cannot follow the assessed case version"
            }
            return ResolutionOutcomeProofAccepted(
                id = snapshot.id,
                runId = snapshot.runId,
                sequence = snapshot.sequence,
                type = ResolutionRunEventType.OUTCOME_PROOF_ACCEPTED,
                fromState = snapshot.fromState,
                toState = snapshot.toState,
                runCaseStreamVersion = snapshot.runCaseStreamVersion,
                caseStreamVersion = snapshot.caseStreamVersion,
                fact = snapshot.fact,
                expectedValue = snapshot.expectedValue,
                factId = snapshot.factId,
                observationId = snapshot.observationId,
                observationStreamVersion = snapshot.observationStreamVersion,
                factStreamVersion = snapshot.factStreamVersion,
                acceptedAt = snapshot.acceptedAt,
            )
        }
    }
}
