package org.ergon.controlplane.resolution.adapter.inbound.http

import jakarta.servlet.http.HttpSession
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.CancelSupervisedRunCommand
import org.ergon.controlplane.resolution.application.RunCancellationReview
import org.ergon.controlplane.resolution.application.RunCancellationService
import org.ergon.controlplane.resolution.application.RunControlFence
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.resolution.domain.ResolutionRunCancellationReason
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

/** Private session boundary; existing BFF security enforces authentication and CSRF before command dispatch. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunCancellationController(
    private val actors: AuthenticatedHumanActorResolver,
    private val cancellations: RunCancellationService,
) {
    /** Advisory only, including when rollout is disabled; never discloses foreign run existence. */
    @GetMapping("/cancellation-review")
    fun review(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<RunCancellationReview> =
        ResponseEntity
            .ok()
            .cacheControl(
                CacheControl.noStore(),
            ).body(
                cancellations.review(
                    tenantId,
                    runId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                ),
            )

    /** Returns only immutable acknowledgement; explicit replay may first apply previously absent exact intent. */
    @PostMapping("/cancellation-commands")
    fun cancel(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
        @RequestBody body: BrowserRunCancellationRequest,
        session: HttpSession,
    ): ResponseEntity<BrowserRunCancellationReceipt> {
        val result =
            cancellations.cancel(
                CancelSupervisedRunCommand(
                    tenantId,
                    runId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    body.commandId,
                    RunSupervisionRevision(body.expectedSupervisionRevision),
                    body.expectedRunVersion,
                ),
                body.control.forSession(session),
            )
        val stored = result.stored
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(
                BrowserRunCancellationReceipt(
                    body.commandId,
                    runId,
                    body.expectedSupervisionRevision,
                    body.expectedRunVersion,
                    stored.event.reason,
                    stored.event.occurredAt,
                    stored.recordedAt,
                ),
            )
    }
}

/** Reviewed fences only: no caller-selected actor, reason, timestamp or capability. */
data class BrowserRunCancellationRequest(
    val commandId: UUID,
    val expectedSupervisionRevision: Long,
    val expectedRunVersion: Long,
    val control: RunControlFence? = null,
)

/** Historical acknowledgement only, without private attribution or restored access. */
data class BrowserRunCancellationReceipt(
    val commandId: UUID,
    val runId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val reason: ResolutionRunCancellationReason,
    val cancelledAt: Instant,
    val recordedAt: Instant,
)
