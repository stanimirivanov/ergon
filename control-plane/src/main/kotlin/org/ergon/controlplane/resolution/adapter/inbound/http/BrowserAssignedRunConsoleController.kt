package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.AssignedRunCursor
import org.ergon.controlplane.resolution.application.AssignedRunDetail
import org.ergon.controlplane.resolution.application.AssignedRunNotFoundException
import org.ergon.controlplane.resolution.application.AssignedRunOverview
import org.ergon.controlplane.resolution.application.AssignedRunPage
import org.ergon.controlplane.resolution.application.RecordedRunTransition
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
import org.springframework.web.bind.annotation.PostMapping
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
     * Returns a recorded start/state, bounded transition and receipt snapshot after exact
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
    @PostMapping("/{runId}/proof-finalizations")
    fun proofUnavailable(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(unavailableProblem())

    @PostMapping("/{runId}/approval-requests/{requestId}/decisions")
    fun approvalUnavailable(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(unavailableProblem())

    @GetMapping("/{runId}/handover-candidates")
    fun candidatesUnavailable(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(unavailableProblem())

    @PostMapping("/{runId}/supervisor-handovers")
    fun handoverUnavailable(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(unavailableProblem())

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
    val supervisionRevision: Long,
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
 * This projection excludes unrelated later evidence, human approval identities,
 * authorization consumption and connector payloads. Execution is a terminal
 * connector receipt, not a projected transition or independent outcome proof.
 * A null execution means no receipt; null timing means unmeasured, not zero.
 * Transitions form the complete immutable prefix through [stateVersion];
 * a proof-acceptance transition includes no inspected proof evidence itself.
 * The separate outcomeVerification member supplies only its exact selected
 * post-action citation and distinguishes mutable matching from durable acceptance.
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
    val execution: BrowserAssignedRunExecutionResponse?,
    val transitions: List<BrowserRecordedRunTransitionResponse>,
    val supervisionRevision: Long,
    val handoverSupported: Boolean,
    val approvalReview: BrowserRunApprovalReview,
    val pinnedEvidence: BrowserPinnedRunEvidence,
    val outcomeVerification: BrowserRunOutcomeVerification,
    val proofFinalizationSupported: Boolean,
)

/** Sequence-ordered state facts through the returned state version, not a span tree or proof payload. */
data class BrowserRecordedRunTransitionResponse(
    val sequence: Long,
    val type: String,
    val fromState: String,
    val toState: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
)

private fun RecordedRunTransition.toResponse(): BrowserRecordedRunTransitionResponse =
    BrowserRecordedRunTransitionResponse(sequence, type.name, fromState.name, toState.name, occurredAt, recordedAt)

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
        supervisionRevision = supervisionRevision.value,
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
        execution = execution?.toBrowserExecution(),
        transitions = transitions.map(RecordedRunTransition::toResponse),
        supervisionRevision = supervisionRevision.value,
        handoverSupported = handoverSupported,
        approvalReview = approvalReview.toBrowserReview(),
        pinnedEvidence = pinnedEvidence.toBrowserEvidence(),
        outcomeVerification = outcomeVerification.toBrowserVerification(),
        proofFinalizationSupported = proofFinalizationSupported,
    )
}

/** Cause-free, no-store problems for read validation, identity rejection and protected run absence. */
@RestControllerAdvice(assignableTypes = [BrowserAssignedRunConsoleController::class])
class BrowserAssignedRunConsoleExceptionHandler {
    @ExceptionHandler(AssignedRunNotFoundException::class)
    fun missing(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "assigned-resolution-run-not-found", "Assigned resolution run not found")

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "invalid-assigned-resolution-run-page", "Invalid assigned resolution run page")

    @ExceptionHandler(AuthenticatedHumanActorNotRegisteredException::class)
    fun unregistered(): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.FORBIDDEN,
            "human-actor-not-registered",
            "Actor not registered",
        )

    @ExceptionHandler(UntrustedHumanIdentityIssuerException::class)
    fun issuer(): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.FORBIDDEN,
            "untrusted-human-identity-issuer",
            "Identity rejected",
        )

    @ExceptionHandler(InvalidAuthenticatedHumanIdentityException::class)
    fun identity(): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.FORBIDDEN, "invalid-authenticated-human-identity", "Identity rejected")

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
            ProblemDetail.forStatusAndDetail(status, detail).apply {
                this.type = URI.create("urn:ergon:problem:$type")
                title = detail
            },
        )
}
