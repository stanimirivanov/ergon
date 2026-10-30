package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.DocumentaryBundlePinned
import org.ergon.cases.domain.ErgonCase
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.controlplane.cases.adapter.out.persistence.CaseEventJsonCodec
import org.ergon.controlplane.contracts.application.InvalidResolutionContractDocumentException
import org.ergon.controlplane.contracts.application.ResolutionContractDocumentDecoder
import org.ergon.controlplane.contracts.application.ResolutionContractReferenceValidator
import org.ergon.controlplane.contracts.application.UnregisteredContractReferencesException
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.PinnedScenarioDecoder
import org.ergon.controlplane.evaluation.application.PinnedScenarioInput
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.ergon.controlplane.evaluation.application.UnsupportedScenarioReason
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationFixture
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityPolicyRule
import org.ergon.resolution.domain.DocumentaryBundleMaterial
import org.ergon.resolution.domain.DocumentaryBundlePin
import org.ergon.resolution.domain.DocumentaryClaimValidity
import org.ergon.resolution.domain.PinnedDocumentaryBundle
import org.ergon.resolution.domain.ResolutionPolicy
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import java.time.DateTimeException
import java.time.Instant
import java.util.List.copyOf

/** Verifies the package first, then decodes only material needed by the supported planning evaluator. */
@Component
class PinnedScenarioJsonDecoder(
    private val packages: SimulationFixtureJsonCodec,
    private val contracts: ResolutionContractDocumentDecoder,
    private val references: ResolutionContractReferenceValidator,
    mapper: JsonMapper,
) : PinnedScenarioDecoder {
    private val strict =
        mapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual) { config ->
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }.withCoercionConfig(LogicalType.Enum) { config ->
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            }.build()
    private val events = CaseEventJsonCodec(strict)

    override fun decode(bytes: ByteArray): PinnedScenarioInput = decodeSupported(bytes, EVALUATOR_MATERIAL)

    /** Separate evaluator identity; existing readiness v1 continues to reject all scripts. */
    fun decodeCapability(bytes: ByteArray): PinnedScenarioInput = decodeSupported(bytes, CAPABILITY_EVALUATOR_MATERIAL)

    /** Model suggestions use their own evaluator without changing either earlier evaluator. */
    fun decodeModel(bytes: ByteArray): PinnedScenarioInput = decodeSupported(bytes, MODEL_EVALUATOR_MATERIAL)

    private fun decodeSupported(
        bytes: ByteArray,
        evaluator: String,
    ): PinnedScenarioInput =
        try {
            decodeValidated(packages.decode(bytes).definition, evaluator)
        } catch (_: JacksonException) {
            throw InvalidSimulationFixture()
        } catch (_: IllegalArgumentException) {
            throw InvalidSimulationFixture()
        } catch (_: DateTimeException) {
            throw InvalidSimulationFixture()
        } catch (_: InvalidResolutionContractDocumentException) {
            throw InvalidSimulationFixture()
        } catch (_: UnregisteredContractReferencesException) {
            throw UnsupportedPinnedScenario(UnsupportedScenarioReason.CONTRACT_VOCABULARY)
        }

    private fun decodeValidated(
        definition: String,
        evaluator: String,
    ): PinnedScenarioInput {
        val document = strict.readValue(definition, SimulationPackageDocument::class.java)
        val fixture = document.manifest.toDomain()
        val material = document.materials.associate { it.reference to it.content }
        if (material.getValue(fixture.evaluator) != evaluator) {
            throw UnsupportedPinnedScenario(UnsupportedScenarioReason.EVALUATOR)
        }
        if (evaluator == EVALUATOR_MATERIAL && fixture.scripts.isNotEmpty()) {
            throw UnsupportedPinnedScenario(UnsupportedScenarioReason.EXECUTION_SCRIPT)
        }
        val contract = contracts.decode(material.getValue(fixture.contract))
        require(contract.key.value == fixture.contract.key)
        require(contract.revision.value.toString() == fixture.contract.revision)
        references.validate(contract)
        val prefix =
            read(
                material.getValue(fixture.casePrefix.material),
                ScenarioPrefixDocument::class.java,
                PREFIX_SCHEMA,
            )
        require(prefix.tenantId == fixture.tenantId && prefix.caseId == fixture.casePrefix.caseId)
        require(prefix.events.size.toLong() == fixture.casePrefix.streamVersion && prefix.events.size <= MAX_EVENTS)
        val history = history(prefix, fixture)
        require(history.count { it is CaseOpened } == 1)
        val case = ErgonCase.rehydrate(CaseId(prefix.caseId), TenantId(prefix.tenantId), history)
        require(
            case.pinnedResolutionContract == null ||
                case.pinnedResolutionContract ==
                org.ergon.contracts.domain
                    .ResolutionContractIdentity(contract.key, contract.revision),
        )
        val documentary = documentary(fixture, prefix, history, material)
        return PinnedScenarioInput(
            fixture,
            contract,
            policy(material.getValue(fixture.policy)),
            copyOf(history),
            documentary,
        )
    }

    private fun history(
        prefix: ScenarioPrefixDocument,
        fixture: SimulationFixture,
    ): List<CaseEvent> =
        prefix.events.mapIndexed { index, entry ->
            requireNotNull(entry)
            require(entry.version == index + 1L)
            require(instant(entry.recordedAt) <= fixture.evaluationAt)
            if (entry.type !in SUPPORTED_EVENTS || entry.schemaVersion != 1) {
                throw UnsupportedPinnedScenario(UnsupportedScenarioReason.EVENT)
            }
            events.decode(entry.type, entry.schemaVersion, entry.payload.toString())
        }

    private fun documentary(
        fixture: SimulationFixture,
        prefix: ScenarioPrefixDocument,
        history: List<CaseEvent>,
        material: Map<SimulationArtifact, String>,
    ): List<PinnedDocumentaryBundle> {
        val pins =
            history
                .mapIndexedNotNull { index, event ->
                    if (event is DocumentaryBundlePinned) index + 1L to event else null
                }.toMap()
        require(pins.keys == fixture.documentaryPins.map { it.streamVersion }.toSet())
        val documentary =
            fixture.documentaryPins.map { pin ->
                val event = pins.getValue(pin.streamVersion)
                require(instant(prefix.events[(pin.streamVersion - 1).toInt()].recordedAt) == pin.attachedAt)
                val summary =
                    read(
                        material.getValue(pin.bundle),
                        ScenarioDocumentaryDocument::class.java,
                        DOCUMENTARY_SCHEMA,
                    )
                val reference = DocumentaryBundleRequirement(summary.bundleId, summary.sha256)
                require(reference == DocumentaryBundleRequirement(event.bundleId, event.sha256))
                require(summary.claims.size <= MAX_CLAIMS)
                PinnedDocumentaryBundle(
                    DocumentaryBundlePin(reference, pin.streamVersion, pin.attachedAt),
                    DocumentaryBundleMaterial(
                        reference,
                        instant(summary.assessedAt),
                        copyOf(
                            summary.claims.map {
                                requireNotNull(it)
                                DocumentaryClaimValidity(it.from?.let(::instant), it.until?.let(::instant))
                            },
                        ),
                    ),
                )
            }
        return copyOf(documentary)
    }

    private fun policy(content: String): ResolutionPolicy {
        val document = read(content, ScenarioPolicyDocument::class.java, POLICY_SCHEMA)
        require(document.rules.size <= MAX_RULES)
        return ResolutionPolicy.define(
            ResolutionPolicyRevision.of(document.revision),
            document.rules.map {
                requireNotNull(it)
                CapabilityPolicyRule(CapabilityName.of(it.capability), it.minimumRisk, it.minimumApproval)
            },
        )
    }

    internal fun <T> read(
        content: String,
        type: Class<T>,
        schema: String,
    ): T {
        val tree = requireNotNull(strict.readTree(content))
        require(tree.isObject)
        if (tree.path("schemaVersion").asString() != schema) {
            throw UnsupportedPinnedScenario(UnsupportedScenarioReason.MATERIAL_VERSION)
        }
        return requireNotNull(strict.readValue(content, type))
    }

    private fun instant(value: String): Instant {
        val parsed = Instant.parse(value)
        require(parsed.nano % NANOS_PER_MICROSECOND == 0)
        return parsed
    }

    companion object {
        const val MODEL_EVALUATOR_MATERIAL = "ergon.simulation/model-proposal/v1"
        const val CAPABILITY_EVALUATOR_MATERIAL = "ergon.simulation/capability-proof/v1"
        const val EVALUATOR_MATERIAL = "ergon.simulation/readiness-policy/v1"
        const val PREFIX_SCHEMA = "ergon.simulation/case-prefix/v1"
        const val POLICY_SCHEMA = "ergon.simulation/policy/v1"
        const val DOCUMENTARY_SCHEMA = "ergon.simulation/documentary-validity/v1"
        private const val MAX_EVENTS = 1_000
        private const val MAX_RULES = 100
        private const val MAX_CLAIMS = 1_000
        private const val NANOS_PER_MICROSECOND = 1_000
        private val SUPPORTED_EVENTS =
            setOf(
                "CaseOpened",
                "ObservationRecorded",
                "AccountAccessStateBound",
                "ResolutionContractRevisionPinned",
                "DocumentaryBundlePinned",
            )
    }
}
