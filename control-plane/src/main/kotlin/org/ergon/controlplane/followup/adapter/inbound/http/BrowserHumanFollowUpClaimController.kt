package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpClaimRecording
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Browser-session command boundary for acquiring immutable follow-up ownership. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims")
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "true",
)
class BrowserHumanFollowUpClaimController(
    private val actors: AuthenticatedHumanActorResolver,
    private val claims: HumanFollowUpClaimService,
) {
    /**
     * Claims open work for the authenticated resolver or replays their existing claim.
     *
     * Spring Security validates the session-bound CSRF header before this method.
     * The application service serializes competitors and requires current resolver
     * authority before revealing whether the work item exists.
     */
    @PostMapping
    fun claim(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserHumanFollowUpClaimResponse> {
        val actor = actors.resolve(tenantId, principal)
        val recording = claims.claim(tenantId, workItemId, actor.actor.id.value)
        val status = if (recording.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(recording.toBrowserResponse())
    }
}

/** Browser claim result without provider identity or authority-evidence details. */
data class BrowserHumanFollowUpClaimResponse(
    val claimId: UUID,
    val workItemId: UUID,
    val claimedAt: Instant,
    val recordedAt: Instant,
)

private fun HumanFollowUpClaimRecording.toBrowserResponse(): BrowserHumanFollowUpClaimResponse {
    val stored = storedClaim
    return BrowserHumanFollowUpClaimResponse(
        claimId = stored.claim.id.value,
        workItemId = stored.claim.workItemId.value,
        claimedAt = stored.claim.claimedAt,
        recordedAt = stored.recordedAt,
    )
}
