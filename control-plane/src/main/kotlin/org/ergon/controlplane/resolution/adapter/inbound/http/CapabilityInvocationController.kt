package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.CapabilityInvocationExecution
import org.ergon.controlplane.resolution.application.CapabilityInvocationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Internal orchestration boundary for executing an authorization consumption. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/capability-authorization-consumptions/{consumptionId}/invocations")
class CapabilityInvocationController(
    private val invocations: CapabilityInvocationService,
) {
    /**
     * Invokes reserved work or returns its already durable terminal receipt.
     *
     * The first durable receipt returns `201`; an idempotent replay returns
     * `200` with that same receipt and does not call the connector again.
     */
    @PostMapping
    fun invoke(
        @PathVariable tenantId: UUID,
        @PathVariable consumptionId: UUID,
    ): ResponseEntity<CapabilityInvocationReceiptResponse> {
        val execution = invocations.invoke(tenantId, consumptionId)
        return ResponseEntity
            .status(if (execution.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(execution.toResponse())
    }
}

/** Stable representation of a terminal result reported by a capability connector. */
data class CapabilityInvocationReceiptResponse(
    val authorizationConsumptionId: UUID,
    val authorizationGrantId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val connector: String,
    val idempotencyKey: UUID,
    val outcome: String,
    val providerOperationReference: String,
    val completedAt: Instant,
    val recordedAt: Instant,
)

private fun CapabilityInvocationExecution.toResponse(): CapabilityInvocationReceiptResponse {
    val value = storedReceipt.receipt
    return CapabilityInvocationReceiptResponse(
        authorizationConsumptionId = value.authorizationConsumptionId.value,
        authorizationGrantId = value.authorizationGrantId.value,
        runId = value.runId.value,
        caseId = value.caseId.value,
        policyRevision = value.policyRevision.value,
        stepId = value.stepId.value,
        capability = value.capability.value,
        connector = value.connector.value,
        idempotencyKey = value.idempotencyKey,
        outcome = value.outcome.name,
        providerOperationReference = value.providerOperationReference.value,
        completedAt = value.completedAt,
        recordedAt = storedReceipt.recordedAt,
    )
}
