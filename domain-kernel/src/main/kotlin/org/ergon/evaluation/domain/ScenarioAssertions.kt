package org.ergon.evaluation.domain

import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.StepPolicyDecision
import java.util.List.copyOf
import java.util.UUID

/** Available absence (for example no escalation) is distinct from missing or unsupported observation. */
sealed interface ScenarioObservation<out T> {
    data class Available<T>(
        val value: T,
    ) : ScenarioObservation<T>

    data object Missing : ScenarioObservation<Nothing>

    data object Unsupported : ScenarioObservation<Nothing>

    data object Error : ScenarioObservation<Nothing>
}

/** Ordered gateway calls, including explicit retries. An empty list means observed zero calls. */
class ObservedInvocations(
    capabilities: List<CapabilityName>,
) {
    val capabilities: List<CapabilityName> = copyOf(capabilities)

    init {
        require(capabilities.size <= MAX_CALLS)
    }
}

/** Exact cited fact and documentary references, not model-generated evidence values or validity attestations. */
class ScenarioGrounding(
    factIds: List<UUID>,
    documentary: List<DocumentaryBundleRequirement>,
) {
    val factIds: List<UUID> = copyOf(factIds)
    val documentary: List<DocumentaryBundleRequirement> = copyOf(documentary)

    init {
        require(factIds.size <= MAX_FACT_REFERENCES && documentary.size <= MAX_DOCUMENTARY_REFERENCES)
        require(factIds.distinct().size == factIds.size)
        require(documentary.map { it.bundleId }.distinct().size == documentary.size)
    }
}

/** A recorded assessment and its action boundary; construction deliberately permits contradictory trace data. */
data class ScenarioProof(
    val assessment: ResolutionOutcomeProofAssessment?,
    val receipt: CapabilityInvocationReceipt?,
    val runCaseStreamVersion: Long,
) {
    init {
        require(runCaseStreamVersion > 0)
    }
}

/**
 * Independent observations only. Never populate unavailable fields from fixture expectations.
 * Earlier replay stages do not establish verified closure or escalation; adapters must mark these unsupported.
 */
data class ScenarioAssertionTrace(
    val outcome: ScenarioObservation<SimulationExpectedOutcome> = ScenarioObservation.Missing,
    val invocations: ScenarioObservation<ObservedInvocations> = ScenarioObservation.Missing,
    val policy: ScenarioObservation<StepPolicyDecision> = ScenarioObservation.Missing,
    val grounding: ScenarioObservation<ScenarioGrounding> = ScenarioObservation.Missing,
    val proof: ScenarioObservation<ScenarioProof> = ScenarioObservation.Missing,
    val escalation: ScenarioObservation<ResolutionRunEscalationReason?> = ScenarioObservation.Missing,
)

/** Closed typed checks; expectations must be authored independently from observed runtime decisions. */
sealed interface ScenarioCheck {
    data class Outcome(
        val expected: SimulationExpectedOutcome,
    ) : ScenarioCheck

    class AllowedInvocations(
        allowed: Set<CapabilityName>,
    ) : ScenarioCheck {
        val allowed: Set<CapabilityName> = java.util.Set.copyOf(allowed)

        init {
            require(allowed.size <= MAX_CALLS)
        }
    }

    class ExactInvocations(
        expected: List<CapabilityName>,
    ) : ScenarioCheck {
        val expected: List<CapabilityName> = copyOf(expected)

        init {
            require(expected.size <= MAX_CALLS)
        }
    }

    data class Policy(
        val expected: StepPolicyDecision,
    ) : ScenarioCheck

    data class Grounding(
        val expected: ScenarioGrounding,
    ) : ScenarioCheck

    data class AcceptedProof(
        val condition: FactCondition,
    ) : ScenarioCheck

    data class NoUnprovedClosure(
        val condition: FactCondition,
    ) : ScenarioCheck

    data class Escalation(
        val expected: ResolutionRunEscalationReason?,
    ) : ScenarioCheck

    /** Preserves unknown catalog entries as explicit results; never a script or executable extension. */
    data class Unsupported(
        val name: String,
    ) : ScenarioCheck {
        init {
            require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")))
        }
    }
}

data class ScenarioAssertion(
    val id: String,
    val required: Boolean,
    val check: ScenarioCheck,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")))
    }
}

/** Bounded ordered catalog; at least one required check prevents vacuous passing summaries. */
class ScenarioAssertions(
    assertions: List<ScenarioAssertion>,
) {
    val assertions: List<ScenarioAssertion> = copyOf(assertions)

    init {
        require(assertions.size in 1..MAX_ASSERTIONS)
        require(assertions.any { it.required })
        require(assertions.map { it.id }.distinct().size == assertions.size)
    }
}

private const val MAX_CALLS = 100
private const val MAX_FACT_REFERENCES = 100
private const val MAX_DOCUMENTARY_REFERENCES = 20
private const val MAX_ASSERTIONS = 100
