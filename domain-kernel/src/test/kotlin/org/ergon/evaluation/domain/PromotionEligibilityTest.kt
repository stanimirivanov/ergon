package org.ergon.evaluation.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PromotionEligibilityTest {
    private val contract = artifact("contract")
    private val policyRef = artifact("policy")
    private val evaluator = artifact("evaluator")
    private val first = artifact("first")
    private val second = artifact("second")
    private val suite = EvaluationSuite(contract, policyRef, listOf(scenario(first), scenario(second)))
    private val policy = EvaluationGatePolicy(listOf(evaluator), true, true)
    private val passed = GateReport(evaluator, true, mapOf("safety" to AssertionStatus.PASS), true, true)

    @Test
    fun `complete exact evidence passes while subsets extra reports and wrong identities fail closed`() {
        val evidence = mapOf(first to GateEvidence(passed, null), second to GateEvidence(passed, null))
        assertThat(assess(evidence).eligible).isTrue()
        val incomplete = assess(evidence - second)
        assertThat(incomplete.scenarios.last().reasons).containsExactly(EligibilityReason.MISSING_REPORT)
        assertThat(assess(evidence + (artifact("extra") to GateEvidence(passed, null))).reasons)
            .containsExactly(EligibilityReason.UNEXPECTED_REPORT)
        val wrong =
            PromotionEligibilityEvaluator.assess(
                suite,
                policy,
                artifact("wrong"),
                artifact("old-policy"),
                evidence,
            )
        assertThat(wrong.eligible).isFalse()
        assertThat(wrong.reasons)
            .containsExactly(EligibilityReason.CONTRACT_MISMATCH, EligibilityReason.POLICY_MISMATCH)
    }

    @Test
    fun `stale evaluators nonpassing assertions and missing or failing budgets cannot qualify`() {
        val variants =
            listOf(
                passed.copy(evaluator = evaluator.copy(revision = "2")),
                passed.copy(passed = false),
                passed.copy(checks = emptyMap()),
                passed.copy(checks = mapOf("safety" to AssertionStatus.UNSUPPORTED)),
                passed.copy(checks = mapOf("safety" to AssertionStatus.ERROR)),
                passed.copy(checks = mapOf("safety" to AssertionStatus.FAIL)),
                passed.copy(costConfiguredAndPassed = false),
                passed.copy(latencyConfiguredAndPassed = false),
            )
        variants.forEach { report ->
            val result = assess(mapOf(first to GateEvidence(report, null), second to GateEvidence(passed, null)))
            assertThat(result.eligible).isFalse()
            assertThat(result.scenarios.first().reasons).isNotEmpty()
        }
        val rejected = assess(mapOf(first to GateEvidence(null, EligibilityReason.REPORT_IDENTITY_MISMATCH)))
        assertThat(rejected.scenarios.first().reasons).containsExactly(EligibilityReason.REPORT_IDENTITY_MISMATCH)
    }

    @Test
    fun `empty duplicate and oversized suites are invalid and source collections cannot change requirements`() {
        assertThatThrownBy { EvaluationSuite(contract, policyRef, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EvaluationSuite(contract, policyRef, listOf(scenario(first), scenario(first))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EvaluationSuite(contract, policyRef, (1..51).map { scenario(artifact("f$it")) }) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EvaluationGatePolicy(emptyList(), false, false) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val checks = mutableListOf("safety")
        val scenarios = mutableListOf(RequiredScenario(first, checks))
        val immutable = EvaluationSuite(contract, policyRef, scenarios)
        checks.clear()
        scenarios.clear()
        assertThat(immutable.scenarios.single().requiredAssertions).containsExactly("safety")
    }

    private fun assess(evidence: Map<SimulationArtifact, GateEvidence>) =
        PromotionEligibilityEvaluator.assess(suite, policy, contract, policyRef, evidence)

    private fun scenario(ref: SimulationArtifact) = RequiredScenario(ref, listOf("safety"))

    private fun artifact(key: String) = SimulationArtifact(key, "1", "a".repeat(64))
}
