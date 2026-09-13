package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Internal command and query adapter for immutable resolution-run starts. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}")
class ResolutionRunController(
    private val service: ResolutionRunService,
) {
    /** Starts a run only from the exact case version supplied in `If-Match`. */
    @PostMapping("/cases/{caseId}/resolution-runs")
    fun start(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
    ): ResponseEntity<ResolutionRunStartResponse> {
        val stored = service.start(tenantId, caseId, parseRunVersion(ifMatch))
        val location = URI.create("/internal/v1/tenants/$tenantId/resolution-runs/${stored.run.id.value}")
        return ResponseEntity.created(location).body(stored.toResponse())
    }

    @GetMapping("/resolution-runs/{runId}")
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ResolutionRunStartResponse = service.get(tenantId, runId).toResponse()
}

/** Signals an `If-Match` value that cannot identify one positive case version. */
class InvalidRunVersionPreconditionException(
    value: String,
) : RuntimeException("If-Match must contain a quoted positive case stream version; received $value")

private fun parseRunVersion(ifMatch: String): Long {
    val match =
        Regex("^\"([0-9]+)\"$").matchEntire(ifMatch.trim())
            ?: throw InvalidRunVersionPreconditionException(ifMatch)
    return match.groupValues[1].toLong().takeIf { it > 0 }
        ?: throw InvalidRunVersionPreconditionException(ifMatch)
}

/**
 * Durable inputs and initial requirement state for one resolution attempt.
 *
 * [initialState] never communicates execution permission; authorization and
 * capability availability are deliberately outside this response.
 */
data class ResolutionRunStartResponse(
    val runId: UUID,
    val caseId: UUID,
    val caseStreamVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val effectiveRisk: String,
    val requiredApproval: String,
    val initialState: String,
    val recordedAt: Instant,
)

private fun StoredResolutionRunStart.toResponse(): ResolutionRunStartResponse {
    val snapshot = run
    return ResolutionRunStartResponse(
        runId = snapshot.id.value,
        caseId = snapshot.caseId.value,
        caseStreamVersion = snapshot.caseStreamVersion,
        contractKey = snapshot.contract.key.value,
        contractRevision = snapshot.contract.revision.value,
        policyRevision = snapshot.policyRevision.value,
        stepId = snapshot.stepId.value,
        capability = snapshot.capability.value,
        effectiveRisk = snapshot.effectiveRisk.name,
        requiredApproval = snapshot.requiredApproval.name,
        initialState = snapshot.initialState.name,
        recordedAt = recordedAt,
    )
}
