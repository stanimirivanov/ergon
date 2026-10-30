package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import tools.jackson.databind.JsonNode
import java.util.UUID

/** Curated stream positions are explicit; recordedAt is independent of the event's occurredAt. */
data class ScenarioEventDocument(
    val version: Long,
    val type: String,
    val schemaVersion: Int,
    val recordedAt: String,
    val payload: JsonNode,
)

data class ScenarioPrefixDocument(
    val schemaVersion: String,
    val tenantId: UUID,
    val caseId: UUID,
    val events: List<ScenarioEventDocument>,
)

data class ScenarioPolicyRule(
    val capability: String,
    val minimumRisk: StepRisk,
    val minimumApproval: ApprovalRequirement,
)

data class ScenarioPolicyDocument(
    val schemaVersion: String,
    val revision: String,
    val rules: List<ScenarioPolicyRule>,
)

data class ScenarioValidityDocument(
    val from: String?,
    val until: String?,
)

/** A curated historical qualification summary; not a new publication of a production evidence bundle. */
data class ScenarioDocumentaryDocument(
    val schemaVersion: String,
    val bundleId: UUID,
    val sha256: String,
    val assessedAt: String,
    val claims: List<ScenarioValidityDocument>,
)
