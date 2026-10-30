package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.controlplane.evaluation.application.GateDocumentCodec
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.InvalidGateDocument
import org.ergon.controlplane.evaluation.application.PreparedGateDocument
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.evaluation.domain.EvaluationGatePolicy
import org.ergon.evaluation.domain.EvaluationSuite
import org.ergon.evaluation.domain.RequiredScenario
import org.ergon.evaluation.domain.SimulationArtifact
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/** Bounded strict v1 transport. Normalization sorts sets, preserves explicit nullable values and hashes UTF-8. */
@Component
class GateDocumentJsonCodec(
    private val strict: PinnedScenarioJsonDecoder,
    private val mapper: JsonMapper,
) : GateDocumentCodec {
    override fun decode(
        kind: GateDocumentKind,
        bytes: ByteArray,
    ): PreparedGateDocument {
        if (bytes.size > MAX_BYTES) throw SimulationFixtureTooLarge()
        return try {
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            if (kind == GateDocumentKind.POLICY) policy(text) else suite(text)
        } catch (_: JacksonException) {
            throw InvalidGateDocument()
        } catch (_: IllegalArgumentException) {
            throw InvalidGateDocument()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw InvalidGateDocument()
        }
    }

    private fun policy(text: String): PreparedGateDocument {
        val wire = strict.read(text, GatePolicyDocument::class.java, POLICY_SCHEMA)
        wire.evaluators.forEach { requireNotNull(it) }
        val policy = EvaluationGatePolicy(wire.evaluators, wire.requireCostBudget, wire.requireLatencyBudget)
        val definition = mapper.writeValueAsString(wire.copy(evaluators = policy.evaluators.sortedWith(ARTIFACT_ORDER)))
        return PreparedGateDocument(GateDocumentKind.POLICY, definition, sha256(definition), policy, null)
    }

    private fun suite(text: String): PreparedGateDocument {
        val wire = strict.read(text, EvaluationSuiteDocument::class.java, SUITE_SCHEMA)
        ResolutionContractKey.of(wire.contract.key)
        val revision = ResolutionContractRevision.of(wire.contract.revision.toInt())
        require(revision.value.toString() == wire.contract.revision)
        val suite =
            EvaluationSuite(
                wire.contract,
                wire.policy,
                wire.scenarios.map {
                    requireNotNull(it)
                    it.requiredAssertions.forEach { id -> requireNotNull(id) }
                    RequiredScenario(it.fixture, it.requiredAssertions)
                },
            )
        val members = suite.scenarios.map { RequiredScenarioDocument(it.fixture, it.requiredAssertions) }
        val normalized = wire.copy(scenarios = members)
        val definition = mapper.writeValueAsString(normalized)
        return PreparedGateDocument(GateDocumentKind.SUITE, definition, sha256(definition), null, suite)
    }

    companion object {
        const val MAX_BYTES = 131_072
        const val POLICY_SCHEMA = "ergon.simulation/gate-policy/v1"
        const val SUITE_SCHEMA = "ergon.simulation/suite/v1"
        private val ARTIFACT_ORDER = compareBy<SimulationArtifact>({ it.key }, { it.revision }, { it.sha256 })
    }
}

data class GatePolicyDocument(
    val schemaVersion: String,
    val evaluators: List<SimulationArtifact>,
    val requireCostBudget: Boolean,
    val requireLatencyBudget: Boolean,
)

data class RequiredScenarioDocument(
    val fixture: SimulationArtifact,
    val requiredAssertions: List<String>,
)

data class EvaluationSuiteDocument(
    val schemaVersion: String,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val scenarios: List<RequiredScenarioDocument>,
)
