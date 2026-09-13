package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.ApprovalRequestView
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Internal command and query adapter for expiring human-approval requests. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}")
class ApprovalRequestController(
    private val service: ApprovalRequestService,
) {
    @PostMapping("/resolution-runs/{runId}/approval-requests")
    fun request(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ResponseEntity<ApprovalRequestResponse> {
        val view = service.request(tenantId, runId)
        val requestId = view.stored.request.id.value
        val location = URI.create("/internal/v1/tenants/$tenantId/approval-requests/$requestId")
        return ResponseEntity.created(location).body(view.toResponse())
    }

    @GetMapping("/approval-requests/{requestId}")
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable requestId: UUID,
    ): ApprovalRequestResponse = service.get(tenantId, requestId).toResponse()
}

/**
 * Immutable approval prerequisite and its status at response time.
 *
 * [requiredAuthority] names the role a future approval must prove; this
 * response contains neither an approver identity nor an approval decision.
 */
data class ApprovalRequestResponse(
    val approvalRequestId: UUID,
    val runId: UUID,
    val stepId: String,
    val requiredAuthority: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val recordedAt: Instant,
    val status: String,
)

private fun ApprovalRequestView.toResponse(): ApprovalRequestResponse {
    val request = stored.request
    return ApprovalRequestResponse(
        approvalRequestId = request.id.value,
        runId = request.runId.value,
        stepId = request.stepId.value,
        requiredAuthority = request.authority.name,
        requestedAt = request.requestedAt,
        expiresAt = request.expiresAt,
        recordedAt = stored.recordedAt,
        status = status.name,
    )
}
