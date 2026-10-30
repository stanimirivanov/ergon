package org.ergon.controlplane.evaluation.adapter.inbound.model

import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.ModelProposalDecoder
import org.ergon.controlplane.evaluation.application.ModelProposalInput
import org.ergon.controlplane.evaluation.application.ModelProposalResponse
import org.ergon.controlplane.evaluation.application.ScriptedProposal
import org.ergon.controlplane.evaluation.application.SimulationFixtureService
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.controlplane.evaluation.application.SuggestedResolutionStep
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.ergon.controlplane.evaluation.application.UnsupportedScenarioReason
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationScriptKind
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.List.copyOf
import java.util.UUID

/** Simulation-only decoding. Configuration, prompts and response text are inert; there is no provider port. */
@Component
class ScriptedModelProposalDecoder(
    private val pinned: PinnedScenarioJsonDecoder,
    private val mapper: JsonMapper,
) : ModelProposalDecoder {
    override fun decode(bytes: ByteArray): ModelProposalInput {
        if (bytes.size > SimulationFixtureService.MAX_BYTES) throw SimulationFixtureTooLarge()
        // Both decoding passes see one snapshot, including when a caller owns a mutable byte array.
        val snapshot = bytes.copyOf()
        try {
            return decodeValidated(snapshot)
        } catch (_: JacksonException) {
            throw InvalidSimulationFixture()
        } catch (_: IllegalArgumentException) {
            throw InvalidSimulationFixture()
        }
    }

    private fun decodeValidated(bytes: ByteArray): ModelProposalInput {
        val input = pinned.decodeModel(bytes)
        val scripts = input.fixture.scripts
        if (scripts.isEmpty() || scripts.any { it.kind != SimulationScriptKind.MODEL }) {
            throw UnsupportedPinnedScenario(UnsupportedScenarioReason.EXECUTION_SCRIPT)
        }
        val reference = requireNotNull(input.fixture.modelConfiguration)
        // The first pass has verified every package/material digest and strict envelope field.
        val document = mapper.readValue(bytes, SimulationPackageDocument::class.java)
        val material = document.materials.associate { it.reference to it.content }
        val configContent = material.getValue(reference)
        val configuration = pinned.read(configContent, ModelConfigurationDocument::class.java, CONFIG_SCHEMA)
        require(configuration.modelId.isNotBlank() && configuration.modelId.length <= MAX_MODEL_ID)
        require(configuration.prompt.isNotBlank() && configuration.prompt.toByteArray().size <= MAX_PROMPT_BYTES)
        require(configuration.maxResponseBytes in 1..MAX_RESPONSE_BYTES)
        val responses =
            scripts.map { script ->
                val scriptContent = material.getValue(script.material)
                val entry = pinned.read(scriptContent, ModelScriptDocument::class.java, SCRIPT_SCHEMA)
                require(entry.configuration == reference)
                val responseBytes = entry.response.toByteArray(Charsets.UTF_8)
                val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(responseBytes))
                val parsed =
                    if (responseBytes.size > configuration.maxResponseBytes) {
                        ScriptedProposal.Malformed
                    } else {
                        proposal(entry.response)
                    }
                ModelProposalResponse(script.material, hash, parsed)
            }
        return ModelProposalInput(input, reference, copyOf(responses))
    }

    private fun proposal(response: String): ScriptedProposal =
        try {
            val document = pinned.read(response, NextStepProposalDocument::class.java, PROPOSAL_SCHEMA)
            if (document.kind != "NEXT_STEP") {
                ScriptedProposal.Unsupported
            } else {
                require(document.factIds.size <= MAX_REFERENCES && document.documentary.size <= MAX_REFERENCES)
                document.factIds.forEach { requireNotNull(it) }
                document.documentary.forEach { requireNotNull(it) }
                require(document.factIds.distinct().size == document.factIds.size)
                require(
                    document.documentary
                        .map { it.bundleId }
                        .distinct()
                        .size == document.documentary.size,
                )
                ScriptedProposal.Parsed(
                    SuggestedResolutionStep(
                        ResolutionStepId.of(document.stepId),
                        CapabilityName.of(document.capability),
                        copyOf(document.factIds),
                        copyOf(document.documentary.map { DocumentaryBundleRequirement(it.bundleId, it.sha256) }),
                    ),
                )
            }
        } catch (_: JacksonException) {
            ScriptedProposal.Malformed
        } catch (_: IllegalArgumentException) {
            ScriptedProposal.Malformed
        } catch (_: UnsupportedPinnedScenario) {
            ScriptedProposal.Unsupported
        }

    companion object {
        const val CONFIG_SCHEMA = "ergon.simulation/model-configuration/v1"
        const val SCRIPT_SCHEMA = "ergon.simulation/model-response/v1"
        const val PROPOSAL_SCHEMA = "ergon.simulation/next-step-proposal/v1"
        private const val MAX_MODEL_ID = 128
        private const val MAX_PROMPT_BYTES = 16_384
        private const val MAX_RESPONSE_BYTES = 16_384
        private const val MAX_REFERENCES = 20
    }
}

/** Descriptive scripted model identity and inert prompt, not executable provider configuration. */
data class ModelConfigurationDocument(
    val schemaVersion: String,
    val modelId: String,
    val prompt: String,
    val maxResponseBytes: Int,
)

data class ModelScriptDocument(
    val schemaVersion: String,
    val configuration: SimulationArtifact,
    val response: String,
)

data class NextStepProposalDocument(
    val schemaVersion: String,
    val kind: String,
    val stepId: String,
    val capability: String,
    val factIds: List<UUID>,
    val documentary: List<ProposalDocumentaryReference>,
)

data class ProposalDocumentaryReference(
    val bundleId: UUID,
    val sha256: String,
)
