package org.ergon.controlplane.evaluation

import org.ergon.controlplane.contracts.adapter.inbound.document.SnakeYamlResolutionContractDocumentDecoder
import org.ergon.controlplane.contracts.adapter.out.registry.BuiltInResolutionContractReferenceRegistry
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.evaluation.adapter.inbound.document.CapabilityScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationAssertionsDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationCheckDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationProgramJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationReportJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationFixtureJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationMaterialDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.adapter.inbound.model.ScriptedModelProposalDecoder
import org.ergon.controlplane.evaluation.adapter.out.simulation.ScriptedCapabilityInvocation
import org.ergon.controlplane.evaluation.application.EvaluationReportRunner
import org.ergon.evaluation.domain.SimulationArtifact
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.HexFormat

internal class EvaluationReportFixtures {
    val f = PinnedScenarioFixtures()
    val mapper = f.mapper
    val packages = SimulationFixtureJsonCodec(mapper)
    val pinned =
        PinnedScenarioJsonDecoder(
            packages,
            SnakeYamlResolutionContractDocumentDecoder(),
            ResolutionContractReferenceValidator(BuiltInResolutionContractReferenceRegistry()),
            mapper,
        )
    val decoder =
        EvaluationProgramJsonDecoder(
            packages,
            pinned,
            CapabilityScenarioJsonDecoder(pinned, mapper),
            ScriptedModelProposalDecoder(pinned, mapper),
            mapper,
        )
    val codec = EvaluationReportJsonCodec(mapper, pinned)
    val runner = EvaluationReportRunner(decoder, ScriptedCapabilityInvocation(), codec)
    val base = f.document(history = f.history(emptyList()))

    fun node(value: Any?): JsonNode = mapper.readTree(mapper.writeValueAsString(value))

    fun check(
        id: String = "not-ready",
        kind: String = "OUTCOME",
        expected: Any? = "NOT_READY",
        required: Boolean = true,
        target: String = "scenario",
    ) = EvaluationCheckDocument(id, required, target, kind, node(expected))

    fun document(
        checks: List<EvaluationCheckDocument> = listOf(check()),
        source: SimulationPackageDocument = base,
    ): SimulationPackageDocument =
        replace(
            source,
            source.manifest.assertions,
            mapper.writeValueAsString(
                EvaluationAssertionsDocument(EvaluationProgramJsonDecoder.ASSERTIONS_SCHEMA, checks),
            ),
        )

    fun bytes(document: SimulationPackageDocument = document()): ByteArray = mapper.writeValueAsBytes(document)

    fun artifact(
        key: String,
        content: String,
    ) = SimulationArtifact(
        key,
        "1",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))),
    )

    fun replace(
        document: SimulationPackageDocument,
        old: SimulationArtifact,
        content: String,
    ): SimulationPackageDocument {
        val replacement = artifact(old.key, content).copy(revision = old.revision)
        val manifest =
            document.manifest.copy(
                assertions = if (old == document.manifest.assertions) replacement else document.manifest.assertions,
                budget = if (old == document.manifest.budget) replacement else document.manifest.budget,
            )
        return document.copy(
            manifest = manifest,
            digest = manifest.toDomain().semanticDigest(),
            materials =
                document.materials.map {
                    if (it.reference == old) SimulationMaterialDocument(replacement, content) else it
                },
        )
    }

    fun budget(
        document: SimulationPackageDocument,
        content: String,
    ): SimulationPackageDocument {
        val reference = artifact("budget", content)
        val manifest = document.manifest.copy(budget = reference)
        return document.copy(
            manifest = manifest,
            digest = manifest.toDomain().semanticDigest(),
            materials = document.materials + SimulationMaterialDocument(reference, content),
        )
    }
}
