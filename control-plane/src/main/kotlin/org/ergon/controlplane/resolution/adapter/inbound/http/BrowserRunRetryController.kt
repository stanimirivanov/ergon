package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.RetrySupervisedRunCommand
import org.ergon.controlplane.resolution.application.ReviewedRunRetryPlan
import org.ergon.controlplane.resolution.application.RunRetryReview
import org.ergon.controlplane.resolution.application.RunRetryReviewService
import org.ergon.controlplane.resolution.application.RunRetryService
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

/** Session/CSRF adapter: no browser actor selection, arbitrary assignment or internal retry API promotion. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunRetryController(
    private val actors: AuthenticatedHumanActorResolver,
    private val reviews: RunRetryReviewService,
    private val retries: RunRetryService,
) {
    /** Current supervisor review discloses planning coordinates, never the new evidence content itself. */
    @GetMapping("/retry-review")
    fun review(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<RunRetryReview> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            reviews.review(
                tenantId,
                runId,
                actors
                    .resolve(tenantId, principal)
                    .actor.id.value,
            ),
        )

    /**
     * First application creates a fresh supervised run, not an executed capability;
     * exact replay acknowledges history.
     */
    @PostMapping("/retry-commands")
    fun retry(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunRetryRequest,
    ): ResponseEntity<BrowserRunRetryReceipt> {
        val result =
            retries.retry(
                RetrySupervisedRunCommand(
                    tenantId,
                    runId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    body.commandId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.expectedRunVersion,
                    body.retryPolicyRevision,
                    body.maximumAttempts,
                    body.plan,
                ),
            )
        val stored = result.stored
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(
                BrowserRunRetryReceipt(
                    stored.command.commandId,
                    stored.command.runId,
                    stored.successorRunId,
                    stored.command.expectedSupervisionRevision.value,
                    stored.command.expectedRunVersion,
                    stored.command.plan.caseStreamVersion,
                    stored.command.retryPolicyRevision,
                    stored.sourceAttemptNumber,
                    stored.command.maximumAttempts,
                    stored.startedAt,
                    stored.recordedAt,
                ),
            )
    }
}

/** Complete reviewed intent; no recipient, successor identity, authority attestation or connector arguments. */
data class BrowserRunRetryRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val expectedRunVersion: Long,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val plan: ReviewedRunRetryPlan,
)

/** Frozen acknowledgement, not the successor's current state or current supervision permission. */
data class BrowserRunRetryReceipt(
    val commandId: UUID,
    val runId: UUID,
    val successorRunId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val caseStreamVersion: Long,
    val retryPolicyRevision: String,
    val sourceAttemptNumber: Int,
    val maximumAttempts: Int,
    val startedAt: Instant,
    val recordedAt: Instant,
)
