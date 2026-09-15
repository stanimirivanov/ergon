package org.ergon.resolution.domain

import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import java.time.Instant

/**
 * One semantically bound fact and its source position in a case stream.
 *
 * Observation and fact versions are retained separately so verification cannot
 * reuse a source captured before the run began. [observedAt] must also follow
 * the action receipt before the evidence can prove its outcome.
 */
data class ResolutionOutcomeEvidence(
    val factId: FactId,
    val fact: ContractFactType,
    val value: ContractFactValue,
    val observationId: ObservationId,
    val observationStreamVersion: Long,
    val factStreamVersion: Long,
    val observedAt: Instant,
    val boundAt: Instant,
) {
    init {
        require(observationStreamVersion > 0) { "observation stream version must be positive" }
        require(factStreamVersion > observationStreamVersion) {
            "outcome fact must follow its source observation"
        }
        require(boundAt >= observedAt) { "outcome fact cannot be bound before its observation" }
    }
}

/** Exact immutable inputs used to assess one run's pinned outcome condition. */
data class ResolutionOutcomeProofBasis(
    val condition: FactCondition,
    val runCaseStreamVersion: Long,
    val actionCompletedAt: Instant,
    val caseStreamVersion: Long,
    val evidence: List<ResolutionOutcomeEvidence>,
) {
    init {
        require(runCaseStreamVersion > 0) { "run case stream version must be positive" }
        require(caseStreamVersion >= runCaseStreamVersion) {
            "assessed case stream cannot precede the run evidence boundary"
        }
    }
}

/** Why the evaluated case snapshot does not yet contain acceptable outcome proof. */
enum class ResolutionOutcomeProofPendingReason {
    ELIGIBLE_EVIDENCE_MISSING,
    VALUE_MISMATCH,
}

/** Deterministic assessment of a pinned outcome condition at one case version. */
sealed interface ResolutionOutcomeProofAssessment {
    val condition: FactCondition
    val caseStreamVersion: Long

    /** A post-action source fact exactly satisfies the pinned condition. */
    data class Accepted(
        override val condition: FactCondition,
        override val caseStreamVersion: Long,
        val evidence: ResolutionOutcomeEvidence,
    ) : ResolutionOutcomeProofAssessment

    /** No eligible post-action fact satisfies the condition yet. */
    data class Pending(
        override val condition: FactCondition,
        override val caseStreamVersion: Long,
        val reason: ResolutionOutcomeProofPendingReason,
        val evidence: ResolutionOutcomeEvidence?,
    ) : ResolutionOutcomeProofAssessment
}

/** Evaluates post-action source evidence without changing run or case state. */
object ResolutionOutcomeProofEvaluator {
    /**
     * Assesses [basis.condition] against the latest eligible fact in stream order.
     *
     * Evidence is eligible only when both its source observation and semantic
     * binding follow the run's evidence boundary and its observation occurred no
     * earlier than action completion. This prevents pre-action evidence from
     * being relabeled as proof after execution.
     */
    fun evaluate(basis: ResolutionOutcomeProofBasis): ResolutionOutcomeProofAssessment {
        val latest =
            basis.evidence
                .asSequence()
                .filter { it.fact == basis.condition.fact }
                .filter { it.observationStreamVersion > basis.runCaseStreamVersion }
                .filter { it.factStreamVersion > basis.runCaseStreamVersion }
                .filter { it.observedAt >= basis.actionCompletedAt }
                .maxByOrNull(ResolutionOutcomeEvidence::factStreamVersion)
                ?: return ResolutionOutcomeProofAssessment.Pending(
                    condition = basis.condition,
                    caseStreamVersion = basis.caseStreamVersion,
                    reason = ResolutionOutcomeProofPendingReason.ELIGIBLE_EVIDENCE_MISSING,
                    evidence = null,
                )
        return if (latest.value == basis.condition.expectedValue) {
            ResolutionOutcomeProofAssessment.Accepted(basis.condition, basis.caseStreamVersion, latest)
        } else {
            ResolutionOutcomeProofAssessment.Pending(
                condition = basis.condition,
                caseStreamVersion = basis.caseStreamVersion,
                reason = ResolutionOutcomeProofPendingReason.VALUE_MISMATCH,
                evidence = latest,
            )
        }
    }
}
