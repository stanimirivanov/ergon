package org.ergon.resolution.domain

import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract

/** Facts available at one case stream version for a deterministic planning decision. */
class ResolutionEvidence private constructor(
    private val values: Map<ContractFactType, ContractFactValue>,
) {
    /** Returns the exact, case-sensitive value for [fact], or `null` when evidence is absent. */
    operator fun get(fact: ContractFactType): ContractFactValue? = values[fact]

    companion object {
        /**
         * Creates an immutable evidence snapshot from the latest value of each fact type.
         *
         * @param facts entries keyed by their registered semantic fact name.
         */
        fun of(facts: Map<ContractFactType, ContractFactValue>): ResolutionEvidence = ResolutionEvidence(facts.toMap())
    }
}

/** Deterministic outcome of evaluating one contract against one evidence snapshot. */
sealed interface ResolutionReadiness {
    /** Every required fact exists and the applicability condition is satisfied. */
    data class Ready(
        val condition: FactCondition,
        val actualValue: ContractFactValue,
    ) : ResolutionReadiness

    /** Required fact types that have no value in the evaluated snapshot, in contract order. */
    @ConsistentCopyVisibility
    data class MissingEvidence private constructor(
        val facts: List<ContractFactType>,
    ) : ResolutionReadiness {
        init {
            require(facts.isNotEmpty()) { "missing evidence must contain at least one fact type" }
        }

        companion object {
            /** Creates an immutable, non-empty snapshot in contract declaration order. */
            fun of(facts: List<ContractFactType>): MissingEvidence = MissingEvidence(facts.toList())
        }
    }

    /** All evidence exists, but the contract's applicability equality is false. */
    data class NotApplicable(
        val condition: FactCondition,
        val actualValue: ContractFactValue,
    ) : ResolutionReadiness
}

/** Evaluates contract readiness without selecting, authorizing, or executing a step. */
object ResolutionReadinessEvaluator {
    /**
     * Evaluates [contract] against one immutable [evidence] snapshot.
     *
     * Missing required evidence takes precedence over applicability so callers
     * never infer non-applicability from an incomplete snapshot. Values use
     * exact equality because contract fact values are deliberately opaque.
     */
    fun evaluate(
        contract: ResolutionContract,
        evidence: ResolutionEvidence,
    ): ResolutionReadiness {
        val missing = contract.requiredEvidence.filter { evidence[it] == null }
        if (missing.isNotEmpty()) {
            return ResolutionReadiness.MissingEvidence.of(missing)
        }

        val actualValue = requireNotNull(evidence[contract.applicability.fact])
        return if (actualValue == contract.applicability.expectedValue) {
            ResolutionReadiness.Ready(contract.applicability, actualValue)
        } else {
            ResolutionReadiness.NotApplicable(contract.applicability, actualValue)
        }
    }
}
