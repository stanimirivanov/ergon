package org.ergon.evaluation.domain

import org.ergon.contracts.domain.FactCondition
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import java.util.List.copyOf

/** ERROR and UNSUPPORTED are never equivalent to an assertion match. */
enum class AssertionStatus { PASS, FAIL, ERROR, UNSUPPORTED }

enum class AssertionExplanation {
    MATCH,
    MISMATCH,
    MISSING_OBSERVATION,
    OBSERVATION_ERROR,
    UNSUPPORTED_OBSERVATION,
    UNSUPPORTED_ASSERTION,
    PROOF_NOT_ACCEPTED,
    INVALID_PROOF_PROVENANCE,
}

data class AssertionResult(
    val id: String,
    val required: Boolean,
    val status: AssertionStatus,
    val explanation: AssertionExplanation,
)

/** Only the evaluator creates summaries; all entries, including optional errors, remain visible. */
class ScenarioAssertionResults internal constructor(
    results: List<AssertionResult>,
) {
    val results: List<AssertionResult> = copyOf(results)
    val requiredChecksPassed: Boolean =
        results.any { it.required } &&
            results
                .filter { it.required }
                .all { it.status == AssertionStatus.PASS }
}

/**
 * Pure comparisons over observations; never invokes the planner, proof evaluator, connector or model under test.
 * Missing/error takes precedence over unsupported when a check requires multiple observations.
 */
object ScenarioAssertionEvaluator {
    fun evaluate(
        assertions: ScenarioAssertions,
        trace: ScenarioAssertionTrace,
    ): ScenarioAssertionResults =
        ScenarioAssertionResults(
            assertions.assertions.map {
                val verdict = evaluate(it.check, trace)
                AssertionResult(it.id, it.required, verdict.status, verdict.explanation)
            },
        )

    private fun evaluate(
        check: ScenarioCheck,
        trace: ScenarioAssertionTrace,
    ): Verdict =
        when (check) {
            is ScenarioCheck.Outcome -> {
                observed(trace.outcome) { match(it == check.expected) }
            }

            is ScenarioCheck.AllowedInvocations -> {
                observed(trace.invocations) { calls ->
                    match(calls.capabilities.all { it in check.allowed })
                }
            }

            is ScenarioCheck.ExactInvocations -> {
                observed(trace.invocations) { match(it.capabilities == check.expected) }
            }

            is ScenarioCheck.Policy -> {
                observed(trace.policy) { match(it == check.expected) }
            }

            is ScenarioCheck.Grounding -> {
                observed(trace.grounding) {
                    match(
                        it.factIds.toSet() == check.expected.factIds.toSet() &&
                            it.documentary.toSet() == check.expected.documentary.toSet(),
                    )
                }
            }

            is ScenarioCheck.AcceptedProof -> {
                observed(trace.proof) { proof(it, check.condition) }
            }

            is ScenarioCheck.NoUnprovedClosure -> {
                observed(trace.outcome) {
                    if (it == SimulationExpectedOutcome.VERIFIED) {
                        observed(trace.proof) { value -> proof(value, check.condition) }
                    } else {
                        match(true)
                    }
                }
            }

            is ScenarioCheck.Escalation -> {
                escalation(check, trace)
            }

            is ScenarioCheck.Unsupported -> {
                Verdict(AssertionStatus.UNSUPPORTED, AssertionExplanation.UNSUPPORTED_ASSERTION)
            }
        }

    private fun escalation(
        check: ScenarioCheck.Escalation,
        trace: ScenarioAssertionTrace,
    ): Verdict {
        val unavailable = listOfNotNull(unavailable(trace.outcome), unavailable(trace.escalation))
        if (unavailable.isNotEmpty()) {
            return unavailable.firstOrNull { it.status == AssertionStatus.ERROR }
                ?: unavailable.first()
        }
        return observed(trace.outcome) { outcome ->
            observed(trace.escalation) { reason ->
                match(reason == check.expected && (outcome == SimulationExpectedOutcome.ESCALATED) == (reason != null))
            }
        }
    }

    private fun proof(
        value: ScenarioProof,
        condition: FactCondition,
    ): Verdict {
        val accepted =
            value.assessment as? ResolutionOutcomeProofAssessment.Accepted
                ?: return Verdict(AssertionStatus.FAIL, AssertionExplanation.PROOF_NOT_ACCEPTED)
        val receipt = value.receipt
        val evidence = accepted.evidence
        val valid =
            receipt != null && receipt.outcome == CapabilityInvocationOutcome.SUCCEEDED &&
                accepted.condition == condition && evidence.fact == condition.fact &&
                evidence.value == condition.expectedValue &&
                evidence.observationStreamVersion > value.runCaseStreamVersion &&
                evidence.factStreamVersion <= accepted.caseStreamVersion &&
                evidence.observedAt >= receipt.completedAt
        return if (valid) match(true) else Verdict(AssertionStatus.FAIL, AssertionExplanation.INVALID_PROOF_PROVENANCE)
    }

    private fun unavailable(value: ScenarioObservation<*>): Verdict? =
        when (value) {
            ScenarioObservation.Missing -> {
                Verdict(AssertionStatus.ERROR, AssertionExplanation.MISSING_OBSERVATION)
            }

            ScenarioObservation.Error -> {
                Verdict(AssertionStatus.ERROR, AssertionExplanation.OBSERVATION_ERROR)
            }

            ScenarioObservation.Unsupported -> {
                Verdict(AssertionStatus.UNSUPPORTED, AssertionExplanation.UNSUPPORTED_OBSERVATION)
            }

            is ScenarioObservation.Available -> {
                null
            }
        }

    private fun <T> observed(
        value: ScenarioObservation<T>,
        evaluate: (T) -> Verdict,
    ): Verdict =
        when (value) {
            is ScenarioObservation.Available -> evaluate(value.value)
            else -> checkNotNull(unavailable(value))
        }

    private fun match(matches: Boolean): Verdict =
        if (matches) {
            Verdict(AssertionStatus.PASS, AssertionExplanation.MATCH)
        } else {
            Verdict(AssertionStatus.FAIL, AssertionExplanation.MISMATCH)
        }
}

private data class Verdict(
    val status: AssertionStatus,
    val explanation: AssertionExplanation,
)
