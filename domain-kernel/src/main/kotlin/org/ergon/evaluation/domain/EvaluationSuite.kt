package org.ergon.evaluation.domain

import java.util.List.copyOf

/** Immutable compatibility and budget requirements; absence of a budget requirement is explicit. */
class EvaluationGatePolicy(
    evaluators: List<SimulationArtifact>,
    val requireCostBudget: Boolean,
    val requireLatencyBudget: Boolean,
) {
    val evaluators: List<SimulationArtifact> = copyOf(evaluators)

    init {
        require(evaluators.size in 1..MAX_EVALUATORS && evaluators.distinct().size == evaluators.size)
    }
}

/** One exact fixture and independent gate-required assertions, including optionally authored report checks. */
class RequiredScenario(
    val fixture: SimulationArtifact,
    requiredAssertions: List<String>,
) {
    val requiredAssertions: List<String> = copyOf(requiredAssertions.sorted())

    init {
        require(requiredAssertions.size in 1..MAX_REQUIRED_CHECKS)
        require(requiredAssertions.distinct().size == requiredAssertions.size)
        require(requiredAssertions.all { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) })
    }
}

/** Fixture digests bind every adapter/script, model configuration, assertion and budget input. No latest lookup. */
class EvaluationSuite(
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    scenarios: List<RequiredScenario>,
) {
    val scenarios: List<RequiredScenario> =
        copyOf(scenarios.sortedWith(compareBy({ it.fixture.key }, { it.fixture.revision })))

    init {
        require(scenarios.size in 1..MAX_SCENARIOS)
        require(scenarios.map { it.fixture.key to it.fixture.revision }.distinct().size == scenarios.size)
    }
}

private const val MAX_EVALUATORS = 20
private const val MAX_REQUIRED_CHECKS = 100
private const val MAX_SCENARIOS = 50
