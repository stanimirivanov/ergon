package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommand
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommandRecording
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRecording
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.StoredHumanFollowUpClaim
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Browser-session command boundary for acquiring immutable follow-up ownership. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}")
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
    @PostMapping("/claims")
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

    /** Applies or exactly replays a revision-checked first claim using the session actor. */
    @PostMapping("/claim-commands")
    fun claimWithCommand(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @RequestBody request: BrowserHumanFollowUpClaimCommandRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserHumanFollowUpClaimCommandResponse> {
        val actor = actors.resolve(tenantId, principal)
        val recording =
            claims.claimWithCommand(
                HumanFollowUpClaimCommand(
                    tenantId,
                    workItemId,
                    actor.actor.id.value,
                    request.commandId,
                    request.expectedOwnershipRevision,
                ),
            )
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

/** Browser-supplied intent; actor and authority evidence are resolved server-side. */
data class BrowserHumanFollowUpClaimCommandRequest(
    val commandId: UUID,
    val expectedOwnershipRevision: Long,
)

/** Durable command result without private resolver or authority attribution. */
data class BrowserHumanFollowUpClaimCommandResponse(
    val commandId: UUID,
    val ownershipRevision: Long,
    val claim: BrowserHumanFollowUpClaimResponse,
)

private fun HumanFollowUpClaimRecording.toBrowserResponse() = storedClaim.toBrowserResponse()

private fun HumanFollowUpClaimCommandRecording.toBrowserResponse() =
    BrowserHumanFollowUpClaimCommandResponse(
        receipt.commandId.value,
        receipt.resultingOwnershipRevision.value,
        receipt.storedClaim.toBrowserResponse(),
    )

private fun StoredHumanFollowUpClaim.toBrowserResponse(): BrowserHumanFollowUpClaimResponse =
    BrowserHumanFollowUpClaimResponse(
        claimId = claim.id.value,
        workItemId = claim.workItemId.value,
        claimedAt = claim.claimedAt,
        recordedAt = recordedAt,
    )
