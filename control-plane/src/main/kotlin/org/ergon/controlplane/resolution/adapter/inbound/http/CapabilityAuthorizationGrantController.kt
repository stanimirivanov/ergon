package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantService
import org.ergon.controlplane.resolution.application.StoredCapabilityAuthorizationGrant
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Internal orchestration boundary for deriving capability authorization from human approval. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/approval-decisions/{decisionId}/authorization-grants")
class CapabilityAuthorizationGrantController(
    private val grants: CapabilityAuthorizationGrantService,
) {
    /**
     * Derives one bounded grant without accepting scope or validity from the caller.
     *
     * All response scope is copied from immutable run and approval history. The
     * returned identifier is not a bearer credential; later consumption must
     * authenticate its caller and atomically spend the stored grant.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun authorize(
        @PathVariable tenantId: UUID,
        @PathVariable decisionId: UUID,
    ): CapabilityAuthorizationGrantResponse = grants.authorize(tenantId, decisionId).toResponse()
}

/** Stable representation of one immutable, bounded capability authorization. */
data class CapabilityAuthorizationGrantResponse(
    val authorizationGrantId: UUID,
    val approvalDecisionId: UUID,
    val approvalRequestId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val authorizedAt: Instant,
    val expiresAt: Instant,
    val recordedAt: Instant,
)

private fun StoredCapabilityAuthorizationGrant.toResponse(): CapabilityAuthorizationGrantResponse {
    val value = grant
    return CapabilityAuthorizationGrantResponse(
        authorizationGrantId = value.id.value,
        approvalDecisionId = value.approvalDecisionId.value,
        approvalRequestId = value.approvalRequestId.value,
        runId = value.runId.value,
        caseId = value.caseId.value,
        policyRevision = value.policyRevision.value,
        stepId = value.stepId.value,
        capability = value.capability.value,
        authorizedAt = value.authorizedAt,
        expiresAt = value.expiresAt,
        recordedAt = recordedAt,
    )
}
