package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionService
import org.ergon.controlplane.resolution.application.StoredCapabilityAuthorizationConsumption
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Internal orchestration boundary for reserving authorized work before connector invocation. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/capability-authorization-grants/{grantId}/consumptions")
class CapabilityAuthorizationConsumptionController(
    private val consumptions: CapabilityAuthorizationConsumptionService,
) {
    /**
     * Consumes one grant using its tenant's configured capability route.
     *
     * The command accepts no connector or scope input. Its result records a
     * reservation only and must not be interpreted as an execution receipt.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun consume(
        @PathVariable tenantId: UUID,
        @PathVariable grantId: UUID,
    ): CapabilityAuthorizationConsumptionResponse = consumptions.consume(tenantId, grantId).toResponse()
}

/** Stable representation of an authorization grant spent before execution. */
data class CapabilityAuthorizationConsumptionResponse(
    val authorizationConsumptionId: UUID,
    val authorizationGrantId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val connector: String,
    val grantAuthorizedAt: Instant,
    val grantExpiresAt: Instant,
    val consumedAt: Instant,
    val recordedAt: Instant,
)

private fun StoredCapabilityAuthorizationConsumption.toResponse(): CapabilityAuthorizationConsumptionResponse {
    val value = consumption
    return CapabilityAuthorizationConsumptionResponse(
        authorizationConsumptionId = value.id.value,
        authorizationGrantId = value.authorizationGrantId.value,
        runId = value.runId.value,
        caseId = value.caseId.value,
        policyRevision = value.policyRevision.value,
        stepId = value.stepId.value,
        capability = value.capability.value,
        connector = value.connector.value,
        grantAuthorizedAt = value.grantAuthorizedAt,
        grantExpiresAt = value.grantExpiresAt,
        consumedAt = value.consumedAt,
        recordedAt = recordedAt,
    )
}
