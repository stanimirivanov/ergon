package org.ergon.evaluation.domain

/** Explicit refusal reasons are not promotion authority or a durable promotion receipt. */
enum class EligibilityReason {
    CONTRACT_MISMATCH,
    POLICY_MISMATCH,
    UNEXPECTED_REPORT,
    MISSING_REPORT,
    REPORT_IDENTITY_MISMATCH,
    INCOMPATIBLE_EVALUATOR,
    REPORT_FAILED,
    REQUIRED_ASSERTION_NOT_PASSED,
    REQUIRED_COST_NOT_PASSED,
    REQUIRED_LATENCY_NOT_PASSED,
}

/** Projection of verified immutable report content, not caller-supplied pass claims. */
data class GateReport(
    val evaluator: SimulationArtifact,
    val passed: Boolean,
    val checks: Map<String, AssertionStatus>,
    val costConfiguredAndPassed: Boolean,
    val latencyConfiguredAndPassed: Boolean,
)

/** A null report preserves missing/rejected evidence rather than creating an empty successful report. */
data class GateEvidence(
    val report: GateReport?,
    val rejection: EligibilityReason?,
) {
    init {
        require((report == null) != (rejection == null))
    }
}

data class ScenarioEligibility(
    val fixture: SimulationArtifact,
    val reasons: List<EligibilityReason>,
)

data class PromotionEligibility(
    val eligible: Boolean,
    val reasons: List<EligibilityReason>,
    val scenarios: List<ScenarioEligibility>,
)

/** Pure exact-set gate. Errors, unsupported checks and incomplete evidence cannot satisfy required conditions. */
object PromotionEligibilityEvaluator {
    fun assess(
        suite: EvaluationSuite,
        policy: EvaluationGatePolicy,
        contract: SimulationArtifact,
        policyReference: SimulationArtifact,
        evidence: Map<SimulationArtifact, GateEvidence>,
    ): PromotionEligibility {
        val reasons =
            buildList {
                if (contract != suite.contract) add(EligibilityReason.CONTRACT_MISMATCH)
                if (policyReference != suite.policy) add(EligibilityReason.POLICY_MISMATCH)
                if (evidence.keys.any { ref -> suite.scenarios.none { it.fixture == ref } }) {
                    add(EligibilityReason.UNEXPECTED_REPORT)
                }
            }
        val scenarios =
            suite.scenarios.map { scenario ->
                ScenarioEligibility(scenario.fixture, assessScenario(scenario, policy, evidence[scenario.fixture]))
            }
        return PromotionEligibility(reasons.isEmpty() && scenarios.all { it.reasons.isEmpty() }, reasons, scenarios)
    }

    private fun assessScenario(
        scenario: RequiredScenario,
        policy: EvaluationGatePolicy,
        evidence: GateEvidence?,
    ): List<EligibilityReason> {
        if (evidence?.report == null) {
            return listOf(evidence?.rejection ?: EligibilityReason.MISSING_REPORT)
        }
        val report = checkNotNull(evidence.report)
        return buildList {
            if (report.evaluator !in policy.evaluators) add(EligibilityReason.INCOMPATIBLE_EVALUATOR)
            if (!report.passed) add(EligibilityReason.REPORT_FAILED)
            if (scenario.requiredAssertions.any { report.checks[it] != AssertionStatus.PASS }) {
                add(EligibilityReason.REQUIRED_ASSERTION_NOT_PASSED)
            }
            if (policy.requireCostBudget && !report.costConfiguredAndPassed) {
                add(EligibilityReason.REQUIRED_COST_NOT_PASSED)
            }
            if (policy.requireLatencyBudget && !report.latencyConfiguredAndPassed) {
                add(EligibilityReason.REQUIRED_LATENCY_NOT_PASSED)
            }
        }
    }
}
