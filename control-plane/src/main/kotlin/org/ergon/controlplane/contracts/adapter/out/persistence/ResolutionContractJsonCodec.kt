package org.ergon.controlplane.contracts.adapter.out.persistence

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.contracts.application.RESOLUTION_CONTRACT_DOCUMENT_SCHEMA
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/** Maps validated contracts to the versioned JSON snapshot stored by PostgreSQL. */
@Component
class ResolutionContractJsonCodec(
    private val objectMapper: ObjectMapper,
) {
    /** Encodes [contract] without carrying YAML syntax into durable runtime meaning. */
    fun encode(contract: ResolutionContract): EncodedResolutionContract =
        EncodedResolutionContract(
            schema = RESOLUTION_CONTRACT_DOCUMENT_SCHEMA,
            definition = objectMapper.writeValueAsString(contract.toSnapshot()),
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
        require(schema == RESOLUTION_CONTRACT_DOCUMENT_SCHEMA) { "unsupported resolution contract schema $schema" }
        val snapshot =
            try {
                objectMapper.readValue(definition, ResolutionContractSnapshotV1::class.java)
            } catch (exception: JacksonException) {
                throw IllegalArgumentException("invalid stored resolution contract definition", exception)
            }
        return ResolutionContract.define(
            identity =
                ResolutionContractIdentity(
                    ResolutionContractKey.of(key),
                    ResolutionContractRevision.of(revision),
                ),
            applicability = snapshot.applicability.toDomain(),
            requiredEvidence = snapshot.requiredEvidence.map(ContractFactType::of),
            steps = snapshot.steps.map(ResolutionStepSnapshot::toDomain),
            outcomeProof = snapshot.outcomeProof.toDomain(),
        )
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
