package org.ergon.controlplane.contracts.adapter.out.persistence

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.contracts.application.DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA
import org.ergon.controlplane.contracts.application.RESOLUTION_CONTRACT_DOCUMENT_SCHEMA
import org.ergon.controlplane.contracts.application.documentSchema
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import java.util.UUID

/** Maps validated contracts to the versioned JSON snapshot stored by PostgreSQL. */
@Component
class ResolutionContractJsonCodec(
    private val objectMapper: JsonMapper,
) {
    private val strict =
        objectMapper
            .rebuild()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual) { config ->
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            }.build()
            .reader()
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    /** Encodes [contract] without carrying YAML syntax into durable runtime meaning. */
    fun encode(contract: ResolutionContract): EncodedResolutionContract =
        EncodedResolutionContract(
            schema = contract.documentSchema(),
            definition =
                objectMapper.writeValueAsString(
                    if (contract.documentaryEvidence.isEmpty()) {
                        contract.toSnapshot()
                    } else {
                        contract.toDocumentarySnapshot()
                    },
                ),
        )

    /**
     * Reconstructs and revalidates a stored contract snapshot.
     *
     * @throws IllegalArgumentException when [schema] is unsupported or stored
     *   values violate the domain invariants.
     */
    fun decode(
        key: String,
        revision: Int,
        schema: String,
        definition: String,
    ): ResolutionContract {
        require(schema in setOf(RESOLUTION_CONTRACT_DOCUMENT_SCHEMA, DOCUMENTARY_RESOLUTION_CONTRACT_SCHEMA)) {
            "unsupported resolution contract schema $schema"
        }
        val snapshot =
            try {
                if (schema == RESOLUTION_CONTRACT_DOCUMENT_SCHEMA) {
                    val legacy =
                        strict
                            .forType(ResolutionContractSnapshotV1::class.java)
                            .readValue<ResolutionContractSnapshotV1>(definition)
                    ResolutionContractSnapshotV2(
                        legacy.applicability,
                        legacy.requiredEvidence,
                        legacy.steps,
                        legacy.outcomeProof,
                        emptyList(),
                    )
                } else {
                    strict
                        .forType(ResolutionContractSnapshotV2::class.java)
                        .readValue<ResolutionContractSnapshotV2>(definition)
                        .also {
                            require(it.documentaryEvidence.isNotEmpty()) { "v1alpha2 requires documentary evidence" }
                        }
                }
            } catch (exception: JacksonException) {
                throw IllegalArgumentException("invalid stored resolution contract definition", exception)
            }
        return ResolutionContract
            .define(
                identity =
                    ResolutionContractIdentity(
                        ResolutionContractKey.of(key),
                        ResolutionContractRevision.of(revision),
                    ),
                applicability = snapshot.applicability.toDomain(),
                requiredEvidence = snapshot.requiredEvidence.map(ContractFactType::of),
                steps = snapshot.steps.map(ResolutionStepSnapshot::toDomain),
                outcomeProof = snapshot.outcomeProof.toDomain(),
            ).let { contract ->
                if (snapshot.documentaryEvidence.isEmpty()) {
                    contract
                } else {
                    contract.requiringDocumentaryEvidence(snapshot.documentaryEvidence.map { it.toDomain() })
                }
            }
    }
}

/** Schema identifier and normalized JSON meaning written as one immutable row. */
data class EncodedResolutionContract(
    val schema: String,
    val definition: String,
)

private data class ResolutionContractSnapshotV1(
    val applicability: FactConditionSnapshot,
    val requiredEvidence: List<String>,
    val steps: List<ResolutionStepSnapshot>,
    val outcomeProof: FactConditionSnapshot,
)

private data class ResolutionContractSnapshotV2(
    val applicability: FactConditionSnapshot,
    val requiredEvidence: List<String>,
    val steps: List<ResolutionStepSnapshot>,
    val outcomeProof: FactConditionSnapshot,
    val documentaryEvidence: List<DocumentaryBundleSnapshot>,
)

private data class DocumentaryBundleSnapshot(
    val bundleId: String,
    val sha256: String,
) {
    fun toDomain(): DocumentaryBundleRequirement {
        val id = UUID.fromString(bundleId)
        require(id.toString() == bundleId) { "bundleId must be canonical" }
        return DocumentaryBundleRequirement(id, sha256)
    }
}

private data class FactConditionSnapshot(
    val fact: String,
    val equals: String,
) {
    fun toDomain() = FactCondition(ContractFactType.of(fact), ContractFactValue.of(equals))
}

private data class ResolutionStepSnapshot(
    val id: String,
    val capability: String,
    val risk: String,
    val approval: String,
) {
    fun toDomain() =
        ResolutionStep(
            id = ResolutionStepId.of(id),
            capability = CapabilityName.of(capability),
            risk = StepRisk.valueOf(risk),
            approval = ApprovalRequirement.valueOf(approval),
        )
}

private fun ResolutionContract.toSnapshot() =
    ResolutionContractSnapshotV1(
        applicability = applicability.toSnapshot(),
        requiredEvidence = requiredEvidence.map { it.value },
        steps = steps.map(ResolutionStep::toSnapshot),
        outcomeProof = outcomeProof.toSnapshot(),
    )

private fun FactCondition.toSnapshot() = FactConditionSnapshot(fact.value, expectedValue.value)

private fun ResolutionStep.toSnapshot() =
    ResolutionStepSnapshot(
        id = id.value,
        capability = capability.value,
        risk = risk.name,
        approval = approval.name,
    )

private fun ResolutionContract.toDocumentarySnapshot() =
    ResolutionContractSnapshotV2(
        applicability.toSnapshot(),
        requiredEvidence.map { it.value },
        steps.map(ResolutionStep::toSnapshot),
        outcomeProof.toSnapshot(),
        documentaryEvidence.map { DocumentaryBundleSnapshot(it.bundleId.toString(), it.sha256) },
    )
