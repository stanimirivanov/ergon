package org.ergon.controlplane.cases.adapter.inbound.http

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.ergon.cases.domain.CaseGoal
import org.ergon.cases.domain.ObservationOrigin
import org.ergon.cases.domain.SourceObservation
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseQueryService
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseWriteResult
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.PinnedResolutionContract
import org.ergon.controlplane.cases.application.RecordConnectorObservationCommand
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Public HTTP adapter for opening cases and reading their source-observation timelines. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/cases")
class CaseController(
    private val commandService: CaseCommandService,
    private val queryService: CaseQueryService,
) {
    @PostMapping
    fun open(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: OpenCaseRequest,
    ): ResponseEntity<CaseWriteResponse> {
        val result =
            commandService.open(
                OpenCaseCommand(
                    tenantId = tenantId,
                    goal = request.goal,
                    initialObservation = request.initialObservation,
                ),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/tenants/$tenantId/cases/${result.caseId}/timeline"))
            .eTag(result.etag())
            .body(result.toResponse())
    }

    @GetMapping("/{caseId}/timeline")
    fun timeline(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
    ): CaseTimelineResponse = queryService.timeline(tenantId, caseId).toResponse()
}

/** Internal connector callback adapter; callers must supply the current quoted stream ETag. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations")
class ConnectorObservationController(
    private val commandService: CaseCommandService,
) {
    @PostMapping
    fun record(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody request: ConnectorObservationRequest,
    ): ResponseEntity<CaseWriteResponse> {
        val result =
            commandService.recordConnectorObservation(
                RecordConnectorObservationCommand(
                    tenantId = tenantId,
                    caseId = caseId,
                    expectedVersion = parseVersion(ifMatch),
                    connector = request.connector,
                    reference = request.reference,
                    content = request.content,
                ),
            )
        return ResponseEntity.ok().eTag(result.etag()).body(result.toResponse())
    }
}

/** HTTP request for opening a case from a requester-authored goal and observation. */
data class OpenCaseRequest(
    @field:NotBlank @field:Size(max = CaseGoal.MAX_LENGTH) val goal: String,
    @field:NotBlank @field:Size(max = SourceObservation.MAX_CONTENT_LENGTH) val initialObservation: String,
)

/** HTTP request containing an attributable observation from one connector. */
data class ConnectorObservationRequest(
    @field:Pattern(regexp = ObservationOrigin.PROVIDER_PATTERN)
    @field:Size(max = ObservationOrigin.MAX_PROVIDER_LENGTH)
    val connector: String,
    @field:NotBlank @field:Size(max = ObservationOrigin.MAX_REFERENCE_LENGTH) val reference: String,
    @field:NotBlank @field:Size(max = SourceObservation.MAX_CONTENT_LENGTH) val content: String,
)

/** HTTP command response carrying the new stream version used as the next ETag. */
data class CaseWriteResponse(
    val caseId: UUID,
    val status: String,
    val streamVersion: Long,
)

/** Stable HTTP representation of a case and its ordered source-observation history. */
data class CaseTimelineResponse(
    val caseId: UUID,
    val goal: String,
    val status: String,
    val streamVersion: Long,
    val resolutionContract: PinnedResolutionContractResponse?,
    val entries: List<CaseTimelineEntryResponse>,
)

/** Stable HTTP representation of the exact contract revision selected for a case. */
data class PinnedResolutionContractResponse(
    val key: String,
    val revision: Int,
    val streamVersion: Long,
    val pinnedAt: Instant,
    val recordedAt: Instant,
)

/** HTTP representation of one durable timeline entry. */
data class CaseTimelineEntryResponse(
    val streamVersion: Long,
    val eventType: String,
    val summary: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val observation: TimelineObservationResponse,
)

/** HTTP representation of an observation and its source attribution. */
data class TimelineObservationResponse(
    val id: UUID,
    val originType: String,
    val provider: String,
    val reference: String?,
    val content: String,
)

/** Signals an `If-Match` value that is not a quoted positive stream version. */
class InvalidVersionPreconditionException(
    value: String,
) : RuntimeException("If-Match must contain a quoted positive stream version; received $value")

internal fun parseVersion(ifMatch: String): Long {
    val match =
        Regex("^\"([0-9]+)\"$").matchEntire(ifMatch.trim())
            ?: throw InvalidVersionPreconditionException(ifMatch)
    return match.groupValues[1].toLong().takeIf { it > 0 }
        ?: throw InvalidVersionPreconditionException(ifMatch)
}

internal fun CaseWriteResult.etag(): String = "\"$streamVersion\""

internal fun CaseWriteResult.toResponse() = CaseWriteResponse(caseId, status, streamVersion)

private fun CaseTimeline.toResponse() =
    CaseTimelineResponse(
        caseId = caseId,
        goal = goal,
        status = status,
        streamVersion = streamVersion,
        resolutionContract = resolutionContract?.toResponse(),
        entries =
            entries.map { entry ->
                CaseTimelineEntryResponse(
                    streamVersion = entry.streamVersion,
                    eventType = entry.eventType,
                    summary = entry.summary,
                    occurredAt = entry.occurredAt,
                    recordedAt = entry.recordedAt,
                    observation =
                        TimelineObservationResponse(
                            id = entry.observation.id,
                            originType = entry.observation.originType,
                            provider = entry.observation.provider,
                            reference = entry.observation.reference,
                            content = entry.observation.content,
                        ),
                )
            },
    )

private fun PinnedResolutionContract.toResponse() =
    PinnedResolutionContractResponse(
        key = key,
        revision = revision,
        streamVersion = streamVersion,
        pinnedAt = pinnedAt,
        recordedAt = recordedAt,
    )
