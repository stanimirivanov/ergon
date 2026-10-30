package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.ergon.controlplane.evaluation.application.CapabilityScenarioStatus
import org.ergon.controlplane.evaluation.application.CapabilityScenarioTrace
import org.ergon.controlplane.evaluation.application.ModelProposalObservation
import org.ergon.controlplane.evaluation.application.ModelProposalResult
import org.ergon.controlplane.evaluation.application.ModelProposalStatus
import org.ergon.controlplane.evaluation.application.ScenarioBudgetAccounting
import org.ergon.controlplane.evaluation.application.ScriptedModelUsage
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.controlplane.resolution.application.CapabilityInvocationCompletion
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.CaseResolutionPlan
import org.ergon.evaluation.domain.BudgetMeasurement
import org.ergon.evaluation.domain.BudgetPricing
import org.ergon.evaluation.domain.BudgetStatus
import org.ergon.evaluation.domain.BudgetUnit
import org.ergon.evaluation.domain.EvaluationBudget
import org.ergon.evaluation.domain.EvaluationBudgetEvaluator
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.resolution.domain.CapabilityInvocationTiming
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ScenarioBudgetAccountingTest {
    private val reference = SimulationArtifact("pricing", "1", "a".repeat(64))
    private val prices = BudgetPricing(reference, "USD", mapOf(BudgetUnit.CALL to 10L))
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val configuration = SimulationArtifact("model", "1", "b".repeat(64))
    private val script = SimulationArtifact("response", "1", "c".repeat(64))
    private val responseHash = "d".repeat(64)
    private val waiting = CaseReadiness.WaitingForContract(UUID(0, 1), 1)
    private val policy = ResolutionPolicyRevision.of("ergon/access/v1")
    private val model =
        ModelProposalResult.Evaluated(
            "e".repeat(64),
            configuration,
            CaseResolutionPlan(waiting, policy, null),
            listOf(ModelProposalObservation(script, responseHash, ModelProposalStatus.MALFORMED_OUTPUT, null)),
        )
    private val measurement =
        ScriptedModelUsage(
            model.fixtureDigest,
            script,
            configuration,
            responseHash,
            reference,
            inputTokens = 2,
            outputTokens = 3,
            latencyNanoseconds = 40,
        )

    @Test
    fun `failure uncertainty and retry all contribute cost and duration despite a shared operation key`() {
        val outcomes = listOf(CapabilityInvocationCallOutcome.UNKNOWN, CapabilityInvocationCallOutcome.FAILED)
        val attempts = outcomes.map { CapabilityInvocationCompletion(it, at, CapabilityInvocationTiming(at, 5)) }
        val trace =
            CapabilityScenarioTrace(
                "a".repeat(64),
                CapabilityScenarioStatus.ACTION_FAILED,
                listOf(UUID(0, 1), UUID(0, 1)),
                attempts,
            )
        val usage = ScenarioBudgetAccounting.capability(trace, reference)
        val result = EvaluationBudgetEvaluator.evaluate(EvaluationBudget("USD", 20, 10), usage, listOf(prices))
        assertThat(result.cost.measurement).isEqualTo(BudgetMeasurement.Known(20))
        assertThat(result.simulatedLatency.measurement).isEqualTo(BudgetMeasurement.Known(10))
        assertThat(result.configuredBudgetsPassed).isTrue()
        assertThat(usage.attempts.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `terminal replay accounts for recorded attempts only and blocked scenarios observe zero calls`() {
        val timing = CapabilityInvocationTiming(at, 5)
        val attempt = CapabilityInvocationCompletion(CapabilityInvocationCallOutcome.SUCCEEDED, at, timing)
        val trace =
            CapabilityScenarioTrace(
                "a".repeat(64),
                CapabilityScenarioStatus.PROOF_PENDING,
                idempotencyKeys = listOf(UUID(0, 1)),
                attempts = listOf(attempt),
            )
        val first = ScenarioBudgetAccounting.capability(trace, reference)
        val replay = ScenarioBudgetAccounting.capability(trace, reference)
        assertThat(first.attempts).hasSize(1)
        val budget = EvaluationBudget("USD", 10, 5)
        assertThat(EvaluationBudgetEvaluator.evaluate(budget, replay, listOf(prices)))
            .isEqualTo(EvaluationBudgetEvaluator.evaluate(budget, first, listOf(prices)))
        val blocked = CapabilityScenarioTrace("a".repeat(64), CapabilityScenarioStatus.WAITING_FOR_APPROVAL)
        assertThat(ScenarioBudgetAccounting.capability(blocked, reference).attempts).isEmpty()
        assertThatIllegalArgumentException().isThrownBy {
            ScenarioBudgetAccounting.capability(trace.copy(attempts = emptyList()), reference)
        }
    }

    @Test
    fun `model malformed output still incurs explicit usage while absent token measurements are unknown`() {
        val rates = mapOf(BudgetUnit.INPUT_TOKEN to 2L, BudgetUnit.OUTPUT_TOKEN to 3L)
        val pricing = BudgetPricing(reference, "USD", rates)
        val usage = ScenarioBudgetAccounting.model(model, listOf(measurement), reference)
        val budget = EvaluationBudget("USD", 13, 40)
        assertThat(EvaluationBudgetEvaluator.evaluate(budget, usage, listOf(pricing)).configuredBudgetsPassed).isTrue()
        val missing = ScenarioBudgetAccounting.model(model, emptyList(), reference)
        val result = EvaluationBudgetEvaluator.evaluate(budget, missing, listOf(pricing))
        assertThat(result.cost.status).isEqualTo(BudgetStatus.UNKNOWN)
        assertThat(result.simulatedLatency.status).isEqualTo(BudgetStatus.UNKNOWN)
    }

    @Test
    fun `model usage binds fixture configuration script and exact response digest`() {
        val invalid =
            listOf(
                measurement.copy(fixtureDigest = "f".repeat(64)),
                measurement.copy(configuration = configuration.copy(revision = "2")),
                measurement.copy(script = script.copy(revision = "2")),
                measurement.copy(responseSha256 = "f".repeat(64)),
                measurement.copy(inputTokens = -1),
            )
        invalid.forEach { entry ->
            assertThatIllegalArgumentException().isThrownBy {
                ScenarioBudgetAccounting.model(model, listOf(entry), reference)
            }
        }
        assertThatIllegalArgumentException().isThrownBy {
            ScenarioBudgetAccounting.model(model, listOf(measurement, measurement), reference)
        }
    }
}
