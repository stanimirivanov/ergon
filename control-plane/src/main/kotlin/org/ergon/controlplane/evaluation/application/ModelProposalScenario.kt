package org.ergon.controlplane.evaluation.application

import org.ergon.cases.domain.AccountAccessStateBound
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.resolution.application.CaseResolutionPlan
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.resolution.domain.StepPolicyDecision
import java.util.List.copyOf
import java.util.UUID

/** References only: a proposal cannot provide new facts, authority, approval or proof. */
data class SuggestedResolutionStep(
    val step: ResolutionStepId,
    val capability: CapabilityName,
    val factIds: List<UUID>,
    val documentary: List<DocumentaryBundleRequirement>,
)

/** Invalid model responses are observations, whereas invalid fixture packages fail decoding. */
sealed interface ScriptedProposal {
    data class Parsed(
        val suggestion: SuggestedResolutionStep,
    ) : ScriptedProposal

    data object Malformed : ScriptedProposal

    data object Unsupported : ScriptedProposal
}

data class ModelProposalResponse(
    val script: SimulationArtifact,
    val responseSha256: String,
    val proposal: ScriptedProposal,
)

/** Verified pinned configuration and ordered responses; no provider lookup or live fallback is available. */
data class ModelProposalInput(
    val pinned: PinnedScenarioInput,
    val configuration: SimulationArtifact,
    val responses: List<ModelProposalResponse>,
)

fun interface ModelProposalDecoder {
    fun decode(bytes: ByteArray): ModelProposalInput
}

enum class ModelProposalStatus {
    MALFORMED_OUTPUT,
    UNSUPPORTED_OUTPUT,
    NOT_READY,
    POLICY_DENIED,
    UNPLANNED_STEP,
    UNPLANNED_CAPABILITY,
    INVALID_REFERENCES,
    CONSISTENT_PROPOSAL,
}

/** Consistency with the plan is advisory, never an execution permission or a passing evaluation. */
data class ModelProposalObservation(
    val script: SimulationArtifact,
    val responseSha256: String,
    val status: ModelProposalStatus,
    val suggestion: SuggestedResolutionStep?,
)

sealed interface ModelProposalResult {
    data class Evaluated(
        val fixtureDigest: String,
        val configuration: SimulationArtifact,
        val plan: CaseResolutionPlan,
        val observations: List<ModelProposalObservation>,
    ) : ModelProposalResult

    data class Unsupported(
        val reason: UnsupportedScenarioReason,
    ) : ModelProposalResult
}

/** Stateless simulation: evaluates each pinned response against the same production-planned case prefix. */
class ModelProposalScenario(
    private val decoder: ModelProposalDecoder,
) {
    /** Throws [InvalidSimulationFixture] for corrupt envelopes; never executes response text or creates facts. */
    fun replay(bytes: ByteArray): ModelProposalResult =
        try {
            val input = decoder.decode(bytes)
            val planning = PinnedScenarioReplay { input.pinned }.replay(byteArrayOf())
            check(planning is PinnedScenarioReplayResult.Evaluated)
            ModelProposalResult.Evaluated(
                input.pinned.fixture.semanticDigest(),
                input.configuration,
                planning.plan,
                copyOf(input.responses.map { observe(it, input, planning.plan) }),
            )
        } catch (unsupported: UnsupportedPinnedScenario) {
            ModelProposalResult.Unsupported(unsupported.reason)
        }

    private fun observe(
        response: ModelProposalResponse,
        input: ModelProposalInput,
        plan: CaseResolutionPlan,
    ): ModelProposalObservation {
        val suggestion = (response.proposal as? ScriptedProposal.Parsed)?.suggestion
        val status =
            when (response.proposal) {
                ScriptedProposal.Malformed -> ModelProposalStatus.MALFORMED_OUTPUT
                ScriptedProposal.Unsupported -> ModelProposalStatus.UNSUPPORTED_OUTPUT
                is ScriptedProposal.Parsed -> assess(checkNotNull(suggestion), input, plan)
            }
        return ModelProposalObservation(response.script, response.responseSha256, status, suggestion)
    }

    private fun assess(
        suggestion: SuggestedResolutionStep,
        input: ModelProposalInput,
        plan: CaseResolutionPlan,
    ): ModelProposalStatus {
        val planned = plan.nextStep ?: return ModelProposalStatus.NOT_READY
        // The current runtime projects the latest account-state binding. Never accept an older or invented fact.
        val latestFact =
            input.pinned.history
                .filterIsInstance<AccountAccessStateBound>()
                .lastOrNull()
                ?.factId
        val requiredFacts = listOfNotNull(latestFact).toSet()
        val requiredBundles =
            input.pinned.contract.documentaryEvidence
                .toSet()
        return when {
            planned.policyDecision is StepPolicyDecision.Denied -> {
                ModelProposalStatus.POLICY_DENIED
            }

            suggestion.capability != planned.step.capability -> {
                ModelProposalStatus.UNPLANNED_CAPABILITY
            }

            suggestion.step != planned.step.id -> {
                ModelProposalStatus.UNPLANNED_STEP
            }

            suggestion.factIds.toSet() != requiredFacts || suggestion.documentary.toSet() != requiredBundles -> {
                ModelProposalStatus.INVALID_REFERENCES
            }

            else -> {
                ModelProposalStatus.CONSISTENT_PROPOSAL
            }
        }
    }
}
