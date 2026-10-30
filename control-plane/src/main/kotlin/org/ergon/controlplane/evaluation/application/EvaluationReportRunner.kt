package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.AssertionStatus
import org.ergon.evaluation.domain.BudgetMeasurement
import org.ergon.evaluation.domain.BudgetStatus
import org.ergon.evaluation.domain.EvaluationBudgetEvaluator
import org.ergon.evaluation.domain.ScenarioAssertion
import org.ergon.evaluation.domain.ScenarioAssertionEvaluator
import org.ergon.evaluation.domain.ScenarioAssertionTrace
import org.ergon.evaluation.domain.ScenarioAssertions
import org.ergon.evaluation.domain.ScenarioBudgetUsage
import org.ergon.evaluation.domain.ScenarioCheck
import java.util.List.copyOf

/** Bounded offline execution; no clocks, random IDs, production repositories or live adapters. */
class EvaluationReportRunner(
    private val decoder: EvaluationProgramDecoder,
    private val invocations: ScenarioInvocationRunner,
    private val codec: EvaluationReportCodec,
) {
    fun execute(bytes: ByteArray): PreparedEvaluationReport {
        val program = decoder.decode(bytes)
        val replay = EvaluationReplayProjection.replay(program, invocations)
        val fixture = program.pinned.fixture
        val expected =
            TargetedAssertion(
                "scenario",
                ScenarioAssertion("fixture-outcome", true, ScenarioCheck.Outcome(fixture.expectedOutcome)),
            )
        val checks =
            (listOf(expected) + program.assertions).map { entry ->
                // Catalogs require a mandatory check; restore the authored flag after evaluating this target.
                ScenarioAssertionEvaluator
                    .evaluate(
                        ScenarioAssertions(listOf(entry.assertion.copy(required = true))),
                        replay.traces[entry.target] ?: ScenarioAssertionTrace(),
                    ).results
                    .single()
                    .copy(required = entry.assertion.required)
            }
        val budget =
            program.budget?.let { specification ->
                EvaluationBudgetEvaluator.evaluate(specification.limits, replay.usage, specification.pricing)
            }
        val budgetSummary =
            budget?.let { value ->
                EvaluationBudgetSummary(value.currency, dimension(value.cost), dimension(value.simulatedLatency))
            }
        val passed =
            checks.filter { it.required }.all { it.status == AssertionStatus.PASS } &&
                (budget == null || budget.configuredBudgetsPassed)
        return codec.encode(
            EvaluationReportContent(
                REPORT_SCHEMA,
                fixture.semanticDigest(),
                fixture.evaluator,
                fixture.assertions,
                fixture.budget,
                copyOf(replay.observed),
                copyOf(checks),
                budgetSummary,
                passed,
            ),
        )
    }

    private fun dimension(value: org.ergon.evaluation.domain.BudgetDimensionResult): EvaluationBudgetDimension =
        EvaluationBudgetDimension(
            value.limit,
            (value.measurement as? BudgetMeasurement.Known)?.value,
            value.status.name,
            when (val measured = value.measurement) {
                is BudgetMeasurement.Known -> null
                is BudgetMeasurement.Error -> measured.reason.name
                is BudgetMeasurement.Unknown -> measured.reason.name
            },
        )

    companion object {
        const val REPORT_SCHEMA = "ergon.simulation/report/v1"
    }
}

internal data class EvaluationReplayObservation(
    val traces: Map<String, ScenarioAssertionTrace>,
    val observed: List<EvaluationObservedTarget>,
    val usage: ScenarioBudgetUsage?,
)
