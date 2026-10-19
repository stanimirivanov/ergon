package org.ergon.controlplane.resolution.adapter.inbound.http

import jakarta.servlet.http.HttpSession
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.ExecuteRunCommand
import org.ergon.controlplane.resolution.application.ResumeRunExecutionCommand
import org.ergon.controlplane.resolution.application.RunControlFence
import org.ergon.controlplane.resolution.application.RunExecutionAcknowledgement
import org.ergon.controlplane.resolution.application.RunExecutionReview
import org.ergon.controlplane.resolution.application.RunExecutionReviewService
import org.ergon.controlplane.resolution.application.RunExecutionService
import org.ergon.controlplane.resolution.application.RunExecutionStatus
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Session actor and CSRF boundary for reviewed execution, never arbitrary browser-selected tool invocation. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunExecutionController(
    private val actors: AuthenticatedHumanActorResolver,
    private val executions: RunExecutionService,
    private val reviews: RunExecutionReviewService,
) {
    /** Eligibility is an observation, not an authorization token or a provider health claim. */
    @GetMapping("/execution-review")
    fun review(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<RunExecutionReview> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.review(tenantId, runId, actor))
    }

    /** Duplicate command POSTs acknowledge status only. Dispatch recovery is always a separate explicit intent. */
    @PostMapping("/execution-commands")
    fun execute(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunExecutionRequest,
        session: HttpSession,
    ): ResponseEntity<BrowserRunExecutionReceipt> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val result =
            executions.execute(
                ExecuteRunCommand(
                    tenantId,
                    runId,
                    actor,
                    body.commandId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.expectedRunVersion,
                    body.requestId,
                    body.decisionId,
                ),
                body.control.forSession(session),
            )
        return acknowledge(result, body.commandId)
    }

    /** A new attempt UUID and current epoch permit recovery of the original reservation, not fresh authorization. */
    @PostMapping("/execution-commands/{commandId}/attempts")
    @Suppress("LongParameterList") // Keep path identity, principal, immutable intent and session provenance explicit.
    fun resume(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @PathVariable commandId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunExecutionAttemptRequest,
        session: HttpSession,
    ): ResponseEntity<BrowserRunExecutionReceipt> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val result =
            executions.resume(
                ResumeRunExecutionCommand(
                    tenantId,
                    runId,
                    actor,
                    commandId,
                    body.attemptId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.expectedRunVersion,
                ),
                body.control.forSession(session),
            )
        return acknowledge(result, body.attemptId)
    }

    private fun acknowledge(
        result: RunExecutionAcknowledgement,
        attemptId: UUID,
    ): ResponseEntity<BrowserRunExecutionReceipt> {
        val command = result.stored.command
        val event = result.result
        val status = if (result.status == RunExecutionStatus.COMPLETED) HttpStatus.OK else HttpStatus.ACCEPTED
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
            BrowserRunExecutionReceipt(
                command.commandId,
                attemptId,
                command.runId,
                result.status.name,
                result.stored.recordedAt,
                event?.event?.receiptOutcome?.name,
                event?.event?.toState?.name,
                event?.event?.sequence,
                event?.event?.occurredAt,
                event?.recordedAt,
            ),
        )
    }
}

/** Only reviewed immutable coordinates; no actor, connector, credential or arbitrary action arguments. */
data class BrowserRunExecutionRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val expectedRunVersion: Long,
    val requestId: UUID,
    val decisionId: UUID,
    val control: RunControlFence? = null,
)

data class BrowserRunExecutionAttemptRequest(
    val attemptId: UUID,
    val expectedSupervisionRevision: Long,
    val expectedRunVersion: Long,
    val control: RunControlFence? = null,
)

/** Historical sequence-one acknowledgement, not current state, independent proof, or permission to retry. */
data class BrowserRunExecutionReceipt(
    val commandId: UUID,
    val attemptId: UUID,
    val runId: UUID,
    val status: String,
    val admittedRecordedAt: Instant,
    val outcome: String?,
    val resultingState: String?,
    val runSequence: Long?,
    val completedAt: Instant?,
    val resultRecordedAt: Instant?,
)
