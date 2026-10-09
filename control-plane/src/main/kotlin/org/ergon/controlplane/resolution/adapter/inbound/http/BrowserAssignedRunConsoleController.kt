package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunCursor
import org.ergon.controlplane.resolution.application.AssignedRunDetail
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.AssignedRunOverview
import org.ergon.controlplane.resolution.application.AssignedRunPage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Assigned-only browser boundary for durable active-run supervision facts. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs")
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "true",
)
class BrowserAssignedRunConsoleController(
    private val actors: AuthenticatedHumanActorResolver,
    private val console: AssignedRunConsoleService,
) {
    /** Lists active runs assigned to the verified session actor, never tenant-wide work. */
    @GetMapping("/assigned")
    fun list(
        @PathVariable tenantId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(required = false) afterAssignedAt: String?,
        @RequestParam(required = false) afterAssignmentId: UUID?,
    ): ResponseEntity<BrowserAssignedRunPageResponse> {
        val actor = actors.resolve(tenantId, principal)
        val cursor = parseCursor(afterAssignedAt, afterAssignmentId)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(console.list(tenantId, actor.actor.id.value, limit, cursor).toResponse())
    }

    /**
     * Returns a recorded start/current-state snapshot only after exact
     * assignment and current resolver authority have been established.
     */
    @GetMapping("/{runId}/console")
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserAssignedRunDetailResponse> {
        val actor = actors.resolve(tenantId, principal)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(console.get(tenantId, runId, actor.actor.id.value).toResponse())
    }
}

/** Keeps assigned-run reads explicitly unavailable when browser OIDC is off. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs")
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class BrowserAssignedRunUnavailableController {
    @GetMapping("/assigned")
    fun listUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    @GetMapping("/{runId}/console")
    fun detailUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
        @Suppress("UNUSED_PARAMETER") @PathVariable runId: UUID,
    ): ProblemDetail = unavailableProblem()

    private fun unavailableProblem(): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Browser authentication is not configured")
            .apply {
                type = URI.create("urn:ergon:problem:browser-authentication-unavailable")
                title = "Browser authentication unavailable"
            }
}

/** Active run coordinates with recorded, not inferred, state and assignment time. */
data class BrowserAssignedRunOverviewResponse(
    val runId: UUID,
    val caseId: UUID,
    val state: String,
    val stateVersion: Long,
    val stateUpdatedAt: Instant,
    val assignedAt: Instant,
)

/** Opaque-as-a-pair cursor for the next assigned-run page. */
data class BrowserAssignedRunCursorResponse(
    val assignedAt: Instant,
    val assignmentId: UUID,
)

/** Bounded assigned-run discovery without a changing total count. */
data class BrowserAssignedRunPageResponse(
    val entries: List<BrowserAssignedRunOverviewResponse>,
    val nextCursor: BrowserAssignedRunCursorResponse?,
)

/**
 * One run's pinned start and current state, not a live execution trace.
 *
 * This first projection excludes case evidence, human approval identities,
 * authorization consumption, connector payloads, and unrecorded span metrics.
 */
data class BrowserAssignedRunDetailResponse(
    val runId: UUID,
    val caseId: UUID,
    val caseEvidenceStreamVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val effectiveRisk: String,
    val requiredApproval: String,
    val attemptNumber: Int,
    val predecessorRunId: UUID?,
    val startedRecordedAt: Instant,
    val state: String,
    val stateVersion: Long,
    val stateUpdatedAt: Instant,
    val assignedAt: Instant,
)

private fun parseCursor(
    assignedAt: String?,
    assignmentId: UUID?,
): AssignedRunCursor? {
    require((assignedAt == null) == (assignmentId == null)) {
        "afterAssignedAt and afterAssignmentId must be supplied together"
    }
    return if (assignedAt == null) {
        null
    } else {
        val instant =
            runCatching { Instant.parse(assignedAt) }
                .getOrElse { throw IllegalArgumentException("afterAssignedAt must be an ISO-8601 instant") }
        AssignedRunCursor(instant, requireNotNull(assignmentId))
    }
}

private fun AssignedRunPage.toResponse(): BrowserAssignedRunPageResponse =
    BrowserAssignedRunPageResponse(
        entries = entries.map(AssignedRunOverview::toResponse),
        nextCursor = nextCursor?.let { BrowserAssignedRunCursorResponse(it.assignedAt, it.assignmentId) },
    )

private fun AssignedRunOverview.toResponse(): BrowserAssignedRunOverviewResponse =
    BrowserAssignedRunOverviewResponse(
        runId = runId.value,
        caseId = caseId.value,
        state = state.name,
        stateVersion = stateVersion,
        stateUpdatedAt = stateUpdatedAt,
        assignedAt = assignedAt,
    )

private fun AssignedRunDetail.toResponse(): BrowserAssignedRunDetailResponse {
    val run = start.run
    return BrowserAssignedRunDetailResponse(
        runId = run.id.value,
        caseId = run.caseId.value,
        caseEvidenceStreamVersion = run.caseStreamVersion,
        contractKey = run.contract.key.value,
        contractRevision = run.contract.revision.value,
        policyRevision = run.policyRevision.value,
        stepId = run.stepId.value,
        capability = run.capability.value,
        effectiveRisk = run.effectiveRisk.name,
        requiredApproval = run.requiredApproval.name,
        attemptNumber = run.attemptNumber,
        predecessorRunId = run.predecessorRunId?.value,
        startedRecordedAt = start.recordedAt,
        state = state.state.name,
        stateVersion = state.version,
        stateUpdatedAt = state.updatedAt,
        assignedAt = assignment.assignment.assignedAt,
    )
}

/** Non-disclosing browser problems for malformed cursors and protected run absence. */
@RestControllerAdvice(assignableTypes = [BrowserAssignedRunConsoleController::class])
class BrowserAssignedRunConsoleExceptionHandler {
    @ExceptionHandler(AssignedRunNotFoundException::class)
    fun missing(
        @Suppress("UNUSED_PARAMETER") exception: AssignedRunNotFoundException,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Assigned resolution run was not found").apply {
            type = URI.create("urn:ergon:problem:assigned-resolution-run-not-found")
            title = "Assigned resolution run not found"
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:invalid-assigned-resolution-run-page")
            title = "Invalid assigned resolution run page"
        }
}
