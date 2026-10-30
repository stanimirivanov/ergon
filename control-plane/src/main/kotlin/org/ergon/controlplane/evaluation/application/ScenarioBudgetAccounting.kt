package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.BudgetAttempt
import org.ergon.evaluation.domain.BudgetUnit
import org.ergon.evaluation.domain.ScenarioBudgetUsage
import org.ergon.evaluation.domain.SimulationArtifact

/** Explicit pinned model usage; output text is never used to estimate tokens or infer elapsed time. */
data class ScriptedModelUsage(
    val fixtureDigest: String,
    val script: SimulationArtifact,
    val configuration: SimulationArtifact,
    val responseSha256: String,
    val pricing: SimulationArtifact,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val latencyNanoseconds: Long?,
)

/** Projects observed attempts and exact model-response measurements; does not decode material or execute replay. */
object ScenarioBudgetAccounting {
    /** Every attempted gateway call costs one CALL, including failures and uncertainty. Receipts add no charge. */
    fun capability(
        trace: CapabilityScenarioTrace,
        pricing: SimulationArtifact,
    ): ScenarioBudgetUsage {
        require(trace.attempts.size == trace.idempotencyKeys.size) { "incomplete invocation accounting trace" }
        return ScenarioBudgetUsage(
            trace.attempts.mapIndexed { index, attempt ->
                val duration = attempt.timing.elapsedNanoseconds
                BudgetAttempt("connector-$index", pricing, mapOf(BudgetUnit.CALL to 1L), duration)
            },
        )
    }

    /**
     * Missing usage remains unknown for that response. Extra or misbound usage fails rather than changing selection.
     * [defaultPricing] is the explicit pinned schedule for responses without a measurement, not a latest lookup.
     */
    fun model(
        result: ModelProposalResult.Evaluated,
        measurements: List<ScriptedModelUsage>,
        defaultPricing: SimulationArtifact,
    ): ScenarioBudgetUsage {
        require(measurements.size <= MAX_MODEL_CALLS)
        require(measurements.map { it.script }.distinct().size == measurements.size)
        require(result.observations.size <= MAX_MODEL_CALLS)
        val scripts = result.observations.map { it.script }
        require(scripts.distinct().size == scripts.size)
        require(measurements.all { it.script in scripts })
        val byScript = measurements.associateBy { it.script }
        return ScenarioBudgetUsage(
            result.observations.mapIndexed { index, observation ->
                val usage = byScript[observation.script]
                if (usage != null) {
                    require(usage.fixtureDigest == result.fixtureDigest)
                    require(usage.configuration == result.configuration)
                    require(usage.responseSha256 == observation.responseSha256)
                }
                BudgetAttempt(
                    "model-$index",
                    usage?.pricing ?: defaultPricing,
                    mapOf(BudgetUnit.INPUT_TOKEN to usage?.inputTokens, BudgetUnit.OUTPUT_TOKEN to usage?.outputTokens),
                    usage?.latencyNanoseconds,
                )
            },
        )
    }

    private const val MAX_MODEL_CALLS = 100
}
