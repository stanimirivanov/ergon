package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.EscalateSupervisedRunCommand
import org.ergon.controlplane.resolution.application.RunEscalationReview
import org.ergon.controlplane.resolution.application.RunEscalationReviewService
import org.ergon.controlplane.resolution.application.RunEscalationService
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.resolution.domain.ResolutionRunEscalationReason
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

/** Confidential session/CSRF boundary; internal bearer routes and private attribution are not promoted. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunEscalationController(
    private val actors: AuthenticatedHumanActorResolver,
    private val reviews: RunEscalationReviewService,
    private val escalations: RunEscalationService,
) {
    /** Returns advisory exhaustion only after current supervision/resolver gates, even when commands are off. */
    @GetMapping("/escalation-review")
    fun review(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<RunEscalationReview> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            reviews.review(
                tenantId,
                runId,
                actors
                    .resolve(tenantId, principal)
                    .actor.id.value,
            ),
        )

    /** First application opens unassigned work; exact replay returns only historical creation facts. */
    @PostMapping("/escalation-commands")
    fun escalate(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunEscalationRequest,
    ): ResponseEntity<BrowserRunEscalationReceipt> {
        val result =
            escalations.escalate(
                EscalateSupervisedRunCommand(
                    tenantId,
                    runId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    body.commandId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.expectedRunVersion,
                    body.sourceAttemptNumber,
                    body.retryPolicyRevision,
                    body.maximumAttempts,
                    body.reason,
                ),
            )
        val stored = result.stored
        val command = stored.command
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(
                BrowserRunEscalationReceipt(
                    command.commandId,
                    command.runId,
                    command.expectedSupervisionRevision.value,
                    command.expectedRunVersion,
                    command.sourceAttemptNumber,
                    command.retryPolicyRevision,
                    command.maximumAttempts,
                    command.reason,
                    stored.escalationEventId,
                    stored.followUpWorkItemId,
                    HumanFollowUpQueueKey.ACCESS_RESTORATION.value,
                    "OPEN",
                    stored.requestedAt,
                    stored.recordedAt,
                ),
            )
    }
}

/** Reviewed exhaustion only; actor, queue, recipient and time are server-owned. */
data class BrowserRunEscalationRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val expectedRunVersion: Long,
    val sourceAttemptNumber: Int,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val reason: ResolutionRunEscalationReason,
)

/**
 * Frozen acknowledgement, not a run/follow-up access token. creationStatus describes
 * the original OPEN item, never its current ownership or lifecycle after claiming.
 */
data class BrowserRunEscalationReceipt(
    val commandId: UUID,
    val runId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val sourceAttemptNumber: Int,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val reason: ResolutionRunEscalationReason,
    val escalationEventId: UUID,
    val followUpWorkItemId: UUID,
    val followUpQueueKey: String,
    val creationStatus: String,
    val requestedAt: Instant,
    val recordedAt: Instant,
)
