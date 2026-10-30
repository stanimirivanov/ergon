package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.PreparedSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureDecoder
import org.ergon.controlplane.evaluation.application.SimulationFixtureService
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationCasePrefix
import org.ergon.evaluation.domain.SimulationDocumentaryPin
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.evaluation.domain.SimulationFixture
import org.ergon.evaluation.domain.SimulationScript
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Strict v1 package decoder shared by ingress and durable reconstruction; material remains inert UTF-8 text. */
@Component
class SimulationFixtureJsonCodec(
    private val mapper: JsonMapper,
) : SimulationFixtureDecoder {
    private val reader =
        mapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual) { config ->
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }.withCoercionConfig(LogicalType.Enum) { config ->
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            }.build()
            .readerFor(SimulationPackageDocument::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    override fun decode(bytes: ByteArray): PreparedSimulationFixture {
        if (bytes.size > SimulationFixtureService.MAX_BYTES) throw SimulationFixtureTooLarge()
        try {
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            val document = requireNotNull(reader.readValue<SimulationPackageDocument>(text))
            document.materials.forEach { requireNotNull(it) }
            document.manifest.documentaryPins.forEach { requireNotNull(it) }
            document.manifest.scripts.forEach { requireNotNull(it) }
            val fixture = document.manifest.toDomain()
            validateMaterials(document, fixture)
            val normalized =
                document.copy(
                    manifest =
                        document.manifest.copy(
                            evaluationAt = fixture.evaluationAt.toString(),
                            documentaryPins =
                                fixture.documentaryPins.map {
                                    SimulationDocumentaryPinDocument(
                                        it.bundle,
                                        it.streamVersion,
                                        it.attachedAt.toString(),
                                    )
                                },
                        ),
                    materials =
                        document.materials.sortedWith(
                            compareBy({ it.reference.key }, { it.reference.revision }, { it.reference.sha256 }),
                        ),
                )
            val definition = mapper.writeValueAsString(normalized)
            if (definition.toByteArray(Charsets.UTF_8).size > SimulationFixtureService.MAX_BYTES) {
                throw SimulationFixtureTooLarge()
            }
            return PreparedSimulationFixture(fixture, definition)
        } catch (_: JacksonException) {
            throw InvalidSimulationFixture()
        } catch (_: IllegalArgumentException) {
            throw InvalidSimulationFixture()
        } catch (_: DateTimeException) {
            throw InvalidSimulationFixture()
        } catch (_: CharacterCodingException) {
            throw InvalidSimulationFixture()
        }
    }

    private fun validateMaterials(
        document: SimulationPackageDocument,
        fixture: SimulationFixture,
    ) {
        require(document.digest == fixture.semanticDigest())
        require(document.provenance.description.isNotBlank())
        require(document.provenance.description.length <= MAX_PROVENANCE)
        materialBytes(document.provenance.description)
        val references = references(fixture)
        require(references.map { it.key to it.revision }.distinct().size == references.size)
        require(document.materials.size == references.size)
        require(document.materials.map { it.reference }.toSet() == references)
        require(
            document.materials
                .map { it.reference }
                .distinct()
                .size == document.materials.size,
        )
        document.materials.forEach { material ->
            val digest = MessageDigest.getInstance("SHA-256").digest(materialBytes(material.content))
            require(HexFormat.of().formatHex(digest) == material.reference.sha256)
        }
    }

    private fun materialBytes(value: String): ByteArray {
        require('\u0000' !in value) { "NUL is not supported by PostgreSQL JSON validation" }
        val encoded = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun references(fixture: SimulationFixture): Set<SimulationArtifact> =
        buildSet {
            add(fixture.contract)
            add(fixture.policy)
            add(fixture.casePrefix.material)
            add(fixture.evaluator)
            add(fixture.assertions)
            addAll(fixture.documentaryPins.map { it.bundle })
            addAll(fixture.scripts.map { it.material })
            fixture.modelConfiguration?.let { add(it) }
            fixture.budget?.let { add(it) }
        }

    private companion object {
        const val MAX_PROVENANCE = 2_000
    }
}

/** Registration transport format. Nullable manifest members must be explicitly present as JSON null. */
data class SimulationPackageDocument(
    val manifest: SimulationManifestDocument,
    val digest: String,
    val materials: List<SimulationMaterialDocument>,
    val provenance: SimulationProvenanceDocument,
)

data class SimulationMaterialDocument(
    val reference: SimulationArtifact,
    val content: String,
)

enum class SimulationProvenanceKind { SYNTHETIC, CURATED_HISTORICAL }

data class SimulationProvenanceDocument(
    val kind: SimulationProvenanceKind,
    val description: String,
)

@Suppress("LongParameterList") // Mirrors the v1 manifest without putting serialization in the domain.
data class SimulationManifestDocument(
    val schemaVersion: String,
    val tenantId: UUID,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val casePrefix: SimulationCasePrefix,
    val evaluator: SimulationArtifact,
    val evaluationAt: String,
    val documentaryPins: List<SimulationDocumentaryPinDocument>,
    val scripts: List<SimulationScript>,
    val modelConfiguration: SimulationArtifact?,
    val assertions: SimulationArtifact,
    val expectedOutcome: SimulationExpectedOutcome,
    val budget: SimulationArtifact?,
) {
    fun toDomain() =
        SimulationFixture(
            schemaVersion,
            tenantId,
            contract,
            policy,
            casePrefix,
            evaluator,
            Instant.parse(evaluationAt),
            documentaryPins.map { SimulationDocumentaryPin(it.bundle, it.streamVersion, Instant.parse(it.attachedAt)) },
            scripts,
            modelConfiguration,
            assertions,
            expectedOutcome,
            budget,
        )
}

/** Wire timestamps must be ISO-8601 strings; the domain enforces exact microsecond precision. */
data class SimulationDocumentaryPinDocument(
    val bundle: SimulationArtifact,
    val streamVersion: Long,
    val attachedAt: String,
)
