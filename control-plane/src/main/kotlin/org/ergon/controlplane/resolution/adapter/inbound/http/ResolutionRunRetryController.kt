package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionRunRetryExecution
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Internal development boundary for explicitly starting a failed run's successor. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/retries")
class ResolutionRunRetryController(
    private val service: ResolutionRunRetryService,
) {
    /**
     * Starts or replays a retry without granting authority or invoking a connector.
     *
     * `If-Match` names the current case version, not the failed run's state
     * version. This internal boundary must not be exposed as a public operator API.
     */
    @PostMapping
    fun retry(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
    ): ResponseEntity<ResolutionRunRetryResponse> {
        val result = service.retry(tenantId, runId, parseRunVersion(ifMatch))
        val location =
            URI.create("/internal/v1/tenants/$tenantId/resolution-runs/${result.replacementRun.run.id.value}")
        return if (result.recording.created) {
            ResponseEntity.created(location).body(result.toResponse())
        } else {
            ResponseEntity.ok().location(location).body(result.toResponse())
        }
    }
}

/** Durable retry link together with the successor's independent start requirements. */
data class ResolutionRunRetryResponse(
    val retryEventId: UUID,
    val failedRunId: UUID,
    val failedRunState: String,
    val failedRunStateVersion: Long,
    val startedAt: Instant,
    val recordedAt: Instant,
    val replacementRun: ResolutionRunStartResponse,
)

private fun ResolutionRunRetryExecution.toResponse(): ResolutionRunRetryResponse =
    ResolutionRunRetryResponse(
        retryEventId = recording.storedEvent.event.id.value,
        failedRunId = recording.storedEvent.event.failedRunId.value,
        failedRunState = recording.failedRunState.state.name,
        failedRunStateVersion = recording.failedRunState.version,
        startedAt = recording.storedEvent.event.occurredAt,
        recordedAt = recording.storedEvent.recordedAt,
        replacementRun = replacementRun.toResponse(),
    )
