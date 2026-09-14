package org.ergon.controlplane.resolution.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.StoredApprovalDecision
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Authenticated HTTP boundary for immutable human responses to approval requests. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/approval-requests/{requestId}/decision")
@SecurityRequirement(name = "bearerAuth")
class ApprovalDecisionController(
    private val actors: AuthenticatedHumanActorResolver,
    private val decisions: ApprovalDecisionService,
) {
    /**
     * Records one response using only the actor derived from the verified bearer token.
     *
     * The request body expresses the decision but cannot name the actor, authority,
     * evidence, run, case, or decision time.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun decide(
        @PathVariable tenantId: UUID,
        @PathVariable requestId: UUID,
        authentication: JwtAuthenticationToken,
        @Valid @RequestBody request: RecordApprovalDecisionRequest,
    ): ApprovalDecisionResponse {
        val actor = actors.resolve(tenantId, authentication)
        return decisions
            .decide(tenantId, requestId, actor.actor.id.value, request.outcome.toDomain())
            .toResponse()
    }
}

/** Request containing only the authenticated human's response. */
data class RecordApprovalDecisionRequest(
    @field:Pattern(regexp = "APPROVED|REJECTED")
    val outcome: String,
)

/** Stable representation of the immutable evidence-backed approval decision. */
data class ApprovalDecisionResponse(
    val approvalDecisionId: UUID,
    val approvalRequestId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val actorId: UUID,
    val authorityEvidenceId: UUID,
    val authority: String,
    val outcome: String,
    val decidedAt: Instant,
    val recordedAt: Instant,
)

private fun String.toDomain() = ApprovalDecisionOutcome.valueOf(this)

private fun StoredApprovalDecision.toResponse(): ApprovalDecisionResponse =
    ApprovalDecisionResponse(
        approvalDecisionId = decision.id.value,
        approvalRequestId = decision.requestId.value,
        runId = decision.runId.value,
        caseId = decision.caseId.value,
        actorId = decision.actorId.value,
        authorityEvidenceId = decision.authorityEvidenceId.value,
        authority = decision.authority.name,
        outcome = decision.outcome.name,
        decidedAt = decision.decidedAt,
        recordedAt = recordedAt,
    )
