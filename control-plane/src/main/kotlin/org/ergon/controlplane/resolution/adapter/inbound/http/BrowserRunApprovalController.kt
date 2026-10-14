package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.DecideRunApprovalCommand
import org.ergon.controlplane.resolution.application.RunApprovalAuthorityMissingException
import org.ergon.controlplane.resolution.application.RunApprovalConflictException
import org.ergon.controlplane.resolution.application.RunApprovalDecisionService
import org.ergon.controlplane.resolution.application.RunApprovalDisabledException
import org.ergon.controlplane.resolution.application.RunApprovalReview
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Session/CSRF boundary for confirmed human prerequisites, never grants or runtime execution. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/approval-requests/{requestId}/decisions")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunApprovalController(
    private val actors: AuthenticatedHumanActorResolver,
    private val approvals: RunApprovalDecisionService,
) {
    /** The session supplies actor identity; exact revision and command identity fence stale/uncertain intent. */
    @PostMapping
    fun decide(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @PathVariable requestId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunApprovalRequest,
    ): ResponseEntity<BrowserRunApprovalReceipt> {
        val actor = actors.resolve(tenantId, principal)
        val result =
            approvals.decide(
                DecideRunApprovalCommand(
                    tenantId,
                    runId,
                    requestId,
                    actor.actor.id.value,
                    body.commandId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.outcome,
                ),
            )
        val decision = result.stored.decision
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(
                BrowserRunApprovalReceipt(
                    body.commandId,
                    runId,
                    requestId,
                    body.expectedSupervisionRevision,
                    decision.decisionId,
                    decision.outcome,
                    decision.decidedAt,
                    decision.recordedAt,
                ),
            )
    }

    @ExceptionHandler(RunApprovalConflictException::class)
    fun conflict() =
        problem(
            HttpStatus.CONFLICT,
            "run-approval-conflict",
            "Approval intent is stale or already answered",
        )

    @ExceptionHandler(RunApprovalAuthorityMissingException::class)
    fun authority() =
        problem(
            HttpStatus.FORBIDDEN,
            "run-approval-authority-missing",
            "Matching approval authority is unavailable",
        )

    @ExceptionHandler(RunApprovalDisabledException::class)
    fun disabled() =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "run-approval-disabled",
            "Browser approval decisions are disabled",
        )

    @ExceptionHandler(
        IllegalArgumentException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid() = problem(HttpStatus.BAD_REQUEST, "invalid-run-approval", "Invalid run approval decision")

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

/** Browser chooses only decision intent; identity, attestation, case and clock remain server-owned. */
data class BrowserRunApprovalRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val outcome: ApprovalDecisionOutcome,
)

/** Exact historical acknowledgement, not present authority, authorization or proof of execution. */
data class BrowserRunApprovalReceipt(
    val commandId: UUID,
    val runId: UUID,
    val requestId: UUID,
    val supervisionRevision: Long,
    val decisionId: UUID,
    val outcome: ApprovalDecisionOutcome,
    val decidedAt: Instant,
    val recordedAt: Instant,
)

/** Current lifecycle and eligibility observation; omitted only by older servers, not equivalent to no request. */
data class BrowserRunApprovalReview(
    val status: String,
    val observedAt: Instant,
    val canDecide: Boolean,
    val request: BrowserRunApprovalRequestSummary?,
    val decision: BrowserRunApprovalDecisionSummary?,
)

data class BrowserRunApprovalRequestSummary(
    val requestId: UUID,
    val authority: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val recordedAt: Instant,
)

/** Attribution is deliberately excluded from this browser-safe historical decision summary. */
data class BrowserRunApprovalDecisionSummary(
    val decisionId: UUID,
    val outcome: String,
    val decidedAt: Instant,
    val recordedAt: Instant,
)

internal fun RunApprovalReview.toBrowserReview(): BrowserRunApprovalReview =
    BrowserRunApprovalReview(
        status.name,
        observedAt,
        canDecide,
        latest?.request?.let {
            BrowserRunApprovalRequestSummary(
                it.request.id.value,
                it.request.authority.name,
                it.request.requestedAt,
                it.request.expiresAt,
                it.recordedAt,
            )
        },
        latest?.decision?.let {
            BrowserRunApprovalDecisionSummary(it.decisionId, it.outcome.name, it.decidedAt, it.recordedAt)
        },
    )
