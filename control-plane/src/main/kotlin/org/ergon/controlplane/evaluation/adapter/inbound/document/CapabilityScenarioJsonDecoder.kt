package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.controlplane.evaluation.application.CapabilityScenario
import org.ergon.controlplane.evaluation.application.CapabilityScenarioDecoder
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.ScenarioAuthority
import org.ergon.controlplane.evaluation.application.ScenarioCall
import org.ergon.controlplane.evaluation.application.ScenarioRoute
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.ergon.controlplane.evaluation.application.UnsupportedScenarioReason
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.evaluation.domain.SimulationScriptKind
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.List.copyOf
import java.util.UUID

/** One bounded connector program, after complete package and pinned readiness material validation. */
@Component
class CapabilityScenarioJsonDecoder(
    private val pinned: PinnedScenarioJsonDecoder,
    private val mapper: JsonMapper,
) : CapabilityScenarioDecoder {
    override fun decode(bytes: ByteArray): CapabilityScenario =
        try {
            decodeValidated(bytes)
        } catch (
            _: JacksonException,
        ) {
            throw InvalidSimulationFixture()
        } catch (
            _: IllegalArgumentException,
        ) {
            throw InvalidSimulationFixture()
        } catch (_: DateTimeException) {
            throw InvalidSimulationFixture()
        }

    private fun decodeValidated(bytes: ByteArray): CapabilityScenario {
        val input = pinned.decodeCapability(bytes)
        val scripts = input.fixture.scripts
        if (scripts.size != 1 || scripts.single().kind != SimulationScriptKind.CONNECTOR) {
            throw UnsupportedPinnedScenario(UnsupportedScenarioReason.EXECUTION_SCRIPT)
        }
        // The first decode validates the complete package; this copy only locates its exact script material.
        val packageDocument = mapper.readValue(bytes, SimulationPackageDocument::class.java)
        val material = packageDocument.materials.single { it.reference == scripts.single().material }.content
        val document = pinned.read(material, CapabilityScenarioDocument::class.java, SCHEMA)
        require(document.requestLifetimeSeconds in 1..MAX_SECONDS)
        require(document.consumeAfterSeconds in 0..MAX_SECONDS)
        require(document.invocationCount in 1..MAX_CALLS && document.calls.size in 1..MAX_CALLS)
        document.calls.forEach {
            requireNotNull(it)
            require(it.elapsedNanoseconds in 0..MAX_NANOS && it.elapsedNanoseconds % NANOS_PER_MICRO == 0L)
        }
        document.authority?.let {
            require(it.fromSeconds in -MAX_SECONDS..MAX_SECONDS && it.untilSeconds in -MAX_SECONDS..MAX_SECONDS)
        }
        require(document.proof.size <= MAX_CALLS && document.proofCaseVersion >= input.fixture.casePrefix.streamVersion)
        val proof =
            document.proof.map {
                requireNotNull(it)
                ResolutionOutcomeEvidence(
                    FactId(it.factId),
                    ContractFactType.of(it.fact),
                    ContractFactValue.of(it.value),
                    ObservationId(it.observationId),
                    it.observationVersion,
                    it.factVersion,
                    instant(it.observedAt),
                    instant(it.boundAt),
                )
            }
        require(proof.all { it.factStreamVersion <= document.proofCaseVersion })
        require(proof.map { it.factId }.distinct().size == proof.size)
        require(proof.map { it.observationId }.distinct().size == proof.size)
        val positions = proof.flatMap { listOf(it.observationStreamVersion, it.factStreamVersion) }
        require(positions.distinct().size == positions.size)
        return CapabilityScenario(
            input,
            document.authority,
            document.decision,
            Duration.ofSeconds(document.requestLifetimeSeconds),
            document.consumeAfterSeconds,
            document.route?.let { ScenarioRoute(CapabilityName.of(it.capability), ConnectorName.of(it.connector)) },
            copyOf(document.calls.map { ScenarioCall(it.outcome, it.elapsedNanoseconds) }),
            document.invocationCount,
            document.proofCaseVersion,
            copyOf(proof),
        )
    }

    private fun instant(value: String): Instant = Instant.parse(value).also { require(it.nano % NANOS_PER_MICRO == 0) }

    companion object {
        const val SCHEMA = "ergon.simulation/capability-scenario/v1"
        private const val MAX_SECONDS = 86_400L
        private const val MAX_CALLS = 100
        private const val MAX_NANOS = 60_000_000_000L
        private const val NANOS_PER_MICRO = 1_000
    }
}

@Suppress("LongParameterList") // Complete bounded scenario program; no arbitrary executable commands.
data class CapabilityScenarioDocument(
    val schemaVersion: String,
    val authority: ScenarioAuthority?,
    val decision: ApprovalDecisionOutcome?,
    val requestLifetimeSeconds: Long,
    val consumeAfterSeconds: Long,
    val route: ScenarioRouteDocument?,
    val calls: List<ScenarioCallDocument>,
    val invocationCount: Int,
    val proofCaseVersion: Long,
    val proof: List<ScenarioProofDocument>,
)

data class ScenarioRouteDocument(
    val capability: String,
    val connector: String,
)

data class ScenarioCallDocument(
    val outcome: CapabilityInvocationCallOutcome,
    val elapsedNanoseconds: Long,
)

@Suppress("LongParameterList") // Mirrors the existing production proof evaluator's evidence coordinates.
data class ScenarioProofDocument(
    val factId: UUID,
    val observationId: UUID,
    val fact: String,
    val value: String,
    val observationVersion: Long,
    val factVersion: Long,
    val observedAt: String,
    val boundAt: String,
)
