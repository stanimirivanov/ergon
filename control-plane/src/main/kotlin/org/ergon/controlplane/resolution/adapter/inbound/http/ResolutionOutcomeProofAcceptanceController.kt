package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAcceptanceRecording
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAcceptanceService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Internal command adapter for durably accepting a run's current outcome proof. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/outcome-proof-acceptances")
class ResolutionOutcomeProofAcceptanceController(
    private val acceptances: ResolutionOutcomeProofAcceptanceService,
) {
    /** Appends verified run and case transitions, or replays the original acceptance. */
    @PostMapping
    fun accept(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ResponseEntity<ResolutionOutcomeProofAcceptanceResponse> {
        val recording = acceptances.accept(tenantId, runId)
        return ResponseEntity
            .status(if (recording.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(recording.toResponse())
    }
}

/** Durable accepted-proof identity, evidence provenance, and resulting run state. */
data class ResolutionOutcomeProofAcceptanceResponse(
    val runId: UUID,
    val eventId: UUID,
    val sequence: Long,
    val eventType: String,
    val fromState: String,
    val toState: String,
    val runCaseStreamVersion: Long,
    val caseStreamVersion: Long,
    val fact: String,
    val expectedValue: String,
    val factId: UUID,
    val observationId: UUID,
    val observationStreamVersion: Long,
    val factStreamVersion: Long,
    val acceptedAt: Instant,
    val recordedAt: Instant,
)

private fun ResolutionOutcomeProofAcceptanceRecording.toResponse(): ResolutionOutcomeProofAcceptanceResponse {
    val event = storedEvent.event
    return ResolutionOutcomeProofAcceptanceResponse(
        runId = event.runId.value,
        eventId = event.id.value,
        sequence = event.sequence,
        eventType = event.type.name,
        fromState = event.fromState.name,
        toState = event.toState.name,
        runCaseStreamVersion = event.runCaseStreamVersion,
        caseStreamVersion = event.caseStreamVersion,
        fact = event.fact.value,
        expectedValue = event.expectedValue.value,
        factId = event.factId.value,
        observationId = event.observationId.value,
        observationStreamVersion = event.observationStreamVersion,
        factStreamVersion = event.factStreamVersion,
        acceptedAt = event.acceptedAt,
        recordedAt = storedEvent.recordedAt,
    )
}
