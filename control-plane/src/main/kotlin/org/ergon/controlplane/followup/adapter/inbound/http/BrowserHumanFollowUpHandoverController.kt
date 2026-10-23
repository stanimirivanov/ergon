package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpHandoverCommand
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverRecipientUnavailableException
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverService
import org.ergon.controlplane.followup.application.HumanFollowUpHandoverUnavailableException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpHandoverException
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Session authentication supplies the sender; shared BFF filters own CSRF and no-store. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/handovers")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserHumanFollowUpHandoverController(
    private val actors: AuthenticatedHumanActorResolver,
    private val handovers: HumanFollowUpHandoverService,
) {
    /** Confirmed exact intent only. Never retry by minting another command identity after uncertainty. */
    @PostMapping
    fun handOver(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @RequestBody request: BrowserHumanFollowUpHandoverRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserHumanFollowUpHandoverResponse> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val recording =
            handovers.handOver(
                HumanFollowUpHandoverCommand(
                    tenantId,
                    workItemId,
                    actor,
                    request.commandId,
                    request.claimId,
                    request.expectedOwnershipRevision,
                    request.recipientActorId,
                ),
            )
        val receipt = recording.receipt
        return ResponseEntity.status(if (recording.created) HttpStatus.CREATED else HttpStatus.OK).body(
            BrowserHumanFollowUpHandoverResponse(
                request.commandId,
                workItemId,
                request.claimId,
                request.expectedOwnershipRevision,
                request.recipientActorId,
                receipt.successorClaimId.value,
                receipt.ownershipRevision.value,
                receipt.handedOverAt,
                receipt.recordedAt,
            ),
        )
    }

    @ExceptionHandler(
        HumanFollowUpHandoverUnavailableException::class,
        InvalidHumanFollowUpHandoverException::class,
        HumanFollowUpHandoverNotFoundException::class,
        HumanFollowUpHandoverConflictException::class,
        HumanFollowUpHandoverRecipientUnavailableException::class,
    )
    fun rejected(exception: RuntimeException): ProblemDetail {
        val (status, type) =
            when (exception) {
                is HumanFollowUpHandoverUnavailableException -> {
                    HttpStatus.SERVICE_UNAVAILABLE to "human-follow-up-handover-unavailable"
                }

                is HumanFollowUpHandoverNotFoundException -> {
                    HttpStatus.NOT_FOUND to "human-follow-up-handover-not-found"
                }

                is HumanFollowUpHandoverConflictException -> {
                    HttpStatus.CONFLICT to "human-follow-up-handover-conflict"
                }

                is HumanFollowUpHandoverRecipientUnavailableException -> {
                    HttpStatus.CONFLICT to "human-follow-up-handover-recipient-unavailable"
                }

                else -> {
                    HttpStatus.BAD_REQUEST to "invalid-human-follow-up-handover"
                }
            }
        return ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
        }
    }
}

/** Recipient is a proposed internal UUID, not authority or an identity-provider subject. */
data class BrowserHumanFollowUpHandoverRequest(
    val commandId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val recipientActorId: UUID,
)

/** Bounded original-intent acknowledgement; excludes sender/recipient authority evidence and provider identity. */
data class BrowserHumanFollowUpHandoverResponse(
    val commandId: UUID,
    val workItemId: UUID,
    val originalClaimId: UUID,
    val expectedOwnershipRevision: Long,
    val recipientActorId: UUID,
    val successorClaimId: UUID,
    val ownershipRevision: Long,
    val handedOverAt: Instant,
    val recordedAt: Instant,
)
