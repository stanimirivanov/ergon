package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultRecording
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Internal command adapter that projects a terminal capability receipt into run state. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/capability-results")
class ResolutionRunCapabilityResultController(
    private val results: ResolutionRunCapabilityResultService,
) {
    /**
     * Records the run event derived from its connector receipt.
     *
     * The first append returns `201`; a replay returns `200` with the original
     * event. A successful receipt reaches `VERIFYING`, not a resolved state.
     */
    @PostMapping
    fun record(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ResponseEntity<ResolutionRunCapabilityResultResponse> {
        val recording = results.record(tenantId, runId)
        return ResponseEntity
            .status(if (recording.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(recording.toResponse())
    }
}

/** Stable representation of the capability-result event and resulting run state. */
data class ResolutionRunCapabilityResultResponse(
    val eventId: UUID,
    val runId: UUID,
    val sequence: Long,
    val eventType: String,
    val fromState: String,
    val toState: String,
    val authorizationConsumptionId: UUID,
    val receiptOutcome: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val currentState: String,
    val stateVersion: Long,
)

private fun ResolutionRunCapabilityResultRecording.toResponse(): ResolutionRunCapabilityResultResponse {
    val value = storedEvent.event
    return ResolutionRunCapabilityResultResponse(
        eventId = value.id.value,
        runId = value.runId.value,
        sequence = value.sequence,
        eventType = value.type.name,
        fromState = value.fromState.name,
        toState = value.toState.name,
        authorizationConsumptionId = value.authorizationConsumptionId.value,
        receiptOutcome = value.receiptOutcome.name,
        occurredAt = value.occurredAt,
        recordedAt = storedEvent.recordedAt,
        currentState = currentState.state.name,
        stateVersion = currentState.version,
    )
}
