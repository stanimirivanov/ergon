package org.ergon.controlplane.contracts.adapter.inbound.document

import org.ergon.controlplane.contracts.application.ContractPromotionIntent
import org.ergon.controlplane.contracts.application.PromotionAuthorityIntent
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.evaluation.domain.SimulationArtifact
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Strict bounded v1 commands; report ordering is normalized for exact command recovery. */
@Component
class PromotionDocumentCodec(
    private val strict: PinnedScenarioJsonDecoder,
    private val mapper: JsonMapper,
) {
    fun authority(bytes: ByteArray): PromotionAuthorityIntent {
        val wire = strict.read(text(bytes), PromotionAuthorityDocument::class.java, AUTHORITY_SCHEMA)
        return PromotionAuthorityIntent(
            wire.actorId,
            wire.sourceProvider,
            wire.sourceReference,
            Instant.parse(wire.expiresAt),
        )
    }

    fun binding(bytes: ByteArray): RequiredSuiteIntent {
        val wire = strict.read(text(bytes), RequiredSuiteDocument::class.java, BINDING_SCHEMA)
        return RequiredSuiteIntent(wire.expectedVersion, wire.suite, wire.rationale)
    }

    fun promotion(bytes: ByteArray): ContractPromotionIntent {
        val wire = strict.read(text(bytes), ContractPromotionDocument::class.java, PROMOTION_SCHEMA)
        val ordered = wire.reports.sortedWith(compareBy({ it.fixture.key }, { it.fixture.revision }))
        val reports = java.util.List.copyOf(ordered)
        return ContractPromotionIntent(
            wire.expectedVersion,
            wire.suite,
            wire.contract,
            wire.policy,
            reports,
            wire.rationale,
        )
    }

    fun encode(value: RequiredSuiteIntent): String =
        mapper.writeValueAsString(
            RequiredSuiteDocument(BINDING_SCHEMA, value.expectedVersion, value.suite, value.rationale),
        )

    fun encode(value: ContractPromotionIntent): String =
        mapper.writeValueAsString(
            ContractPromotionDocument(
                PROMOTION_SCHEMA,
                value.expectedVersion,
                value.suite,
                value.contract,
                value.policy,
                value.reports,
                value.rationale,
            ),
        )

    private fun text(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw SimulationFixtureTooLarge()
        return Charsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }

    companion object {
        const val MAX_BYTES = 131_072
        const val AUTHORITY_SCHEMA = "ergon.contract-promotion/authority/v1"
        const val BINDING_SCHEMA = "ergon.contract-promotion/required-suite/v1"
        const val PROMOTION_SCHEMA = "ergon.contract-promotion/command/v1"
    }
}

data class PromotionAuthorityDocument(
    val schemaVersion: String,
    val actorId: UUID,
    val sourceProvider: String,
    val sourceReference: String,
    val expiresAt: String,
)

data class RequiredSuiteDocument(
    val schemaVersion: String,
    val expectedVersion: Long,
    val suite: SimulationArtifact,
    val rationale: String,
)

data class ContractPromotionDocument(
    val schemaVersion: String,
    val expectedVersion: Long,
    val suite: SimulationArtifact,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val reports: List<SelectedEvaluationReport>,
    val rationale: String,
)
