package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpCompletionCommand
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionService
import org.ergon.controlplane.followup.application.HumanFollowUpCompletionUnavailableException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpCompletionException
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

/** Session actor resolution plus shared BFF CSRF/no-store filters protect completion and replay alike. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/completions")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserHumanFollowUpCompletionController(
    private val actors: AuthenticatedHumanActorResolver,
    private val completions: HumanFollowUpCompletionService,
) {
    /** After uncertainty resend only the original identity and body; the receipt does not restore active access. */
    @PostMapping
    fun complete(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @RequestBody request: BrowserHumanFollowUpCompletionRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserHumanFollowUpCompletionResponse> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val result =
            completions.complete(
                HumanFollowUpCompletionCommand(
                    tenantId,
                    workItemId,
                    actor,
                    request.commandId,
                    request.claimId,
                    request.expectedOwnershipRevision,
                ),
            )
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(
            BrowserHumanFollowUpCompletionResponse(
                request.commandId,
                workItemId,
                request.claimId,
                request.expectedOwnershipRevision,
                result.receipt.completedAt,
                result.receipt.recordedAt,
            ),
        )
    }

    @ExceptionHandler(
        HumanFollowUpCompletionUnavailableException::class,
        InvalidHumanFollowUpCompletionException::class,
        HumanFollowUpCompletionNotFoundException::class,
        HumanFollowUpCompletionConflictException::class,
    )
    fun rejected(exception: RuntimeException): ProblemDetail {
        val (status, type) =
            when (exception) {
                is HumanFollowUpCompletionUnavailableException -> {
                    HttpStatus.SERVICE_UNAVAILABLE to "human-follow-up-completion-unavailable"
                }

                is HumanFollowUpCompletionNotFoundException -> {
                    HttpStatus.NOT_FOUND to "human-follow-up-completion-not-found"
                }

                is HumanFollowUpCompletionConflictException -> {
                    HttpStatus.CONFLICT to "human-follow-up-completion-conflict"
                }

                else -> {
                    HttpStatus.BAD_REQUEST to "invalid-human-follow-up-completion"
                }
            }
        return ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
        }
    }
}

data class BrowserHumanFollowUpCompletionRequest(
    val commandId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
)

/** Bounded historical acknowledgement; excludes actor/authority evidence, notes, case/run and provider identity. */
data class BrowserHumanFollowUpCompletionResponse(
    val commandId: UUID,
    val workItemId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val completedAt: Instant,
    val recordedAt: Instant,
)
