package org.ergon.controlplane.resolution.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRecording
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationService
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Internal development boundary for handing an exhausted failed run to humans. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/escalations")
@SecurityRequirement(name = "bearerAuth")
class ResolutionRunEscalationController(
    private val actors: AuthenticatedHumanActorResolver,
    private val service: ResolutionRunEscalationService,
) {
    /** Requests or idempotently replays escalation without assigning or notifying anyone. */
    @PostMapping
    fun escalate(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        authentication: JwtAuthenticationToken,
    ): ResponseEntity<ResolutionRunEscalationResponse> {
        val actor = actors.resolve(tenantId, authentication)
        val recording = service.escalate(tenantId, runId, actor.actor.id.value)
        val location = URI.create("/internal/v1/tenants/$tenantId/resolution-runs/$runId")
        return if (recording.created) {
            ResponseEntity.created(location).body(recording.toResponse())
        } else {
            ResponseEntity.ok().location(location).body(recording.toResponse())
        }
    }
}

/** Immutable audit facts returned after requesting exhausted-run escalation. */
data class ResolutionRunEscalationResponse(
    val escalationEventId: UUID,
    val runId: UUID,
    val state: String,
    val stateVersion: Long,
    val reason: String,
    val retryPolicyRevision: String,
    val retrySourceAttemptNumber: Int,
    val retryMaximumAttempts: Int,
    val requestedByActorId: UUID,
    val authorityEvidenceId: UUID,
    val requestedAt: Instant,
    val recordedAt: Instant,
)

private fun ResolutionRunEscalationRecording.toResponse(): ResolutionRunEscalationResponse {
    val event = storedEvent.event
    return ResolutionRunEscalationResponse(
        event.id.value,
        event.runId.value,
        currentState.state.name,
        currentState.version,
        event.reason.name,
        event.retryDenial.revision.value,
        event.retryDenial.sourceAttemptNumber,
        event.retryDenial.maximumAttempts,
        event.authorization.actorId.value,
        event.authorization.authorityEvidenceId.value,
        event.occurredAt,
        storedEvent.recordedAt,
    )
}
