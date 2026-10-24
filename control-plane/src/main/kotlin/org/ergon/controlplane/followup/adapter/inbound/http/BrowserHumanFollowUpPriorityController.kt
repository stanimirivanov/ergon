package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.HumanFollowUpPriorityCommand
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityService
import org.ergon.controlplane.followup.application.HumanFollowUpPriorityUnavailableException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpPriorityException
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.followup.domain.HumanFollowUpPriority
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

/** Session identity owns attribution; shared BFF filters enforce CSRF and no-store for new intent and replay. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/priority-assignments")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserHumanFollowUpPriorityController(
    private val actors: AuthenticatedHumanActorResolver,
    private val priorities: HumanFollowUpPriorityService,
) {
    /** Resend only the original ID/body after uncertainty; a historical receipt is not current priority. */
    @PostMapping
    fun assign(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @RequestBody request: BrowserHumanFollowUpPriorityRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserHumanFollowUpPriorityResponse> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val priority =
            HumanFollowUpPriority.entries.find { it.name == request.priority }
                ?: throw InvalidHumanFollowUpPriorityException()
        val result =
            priorities.assign(
                HumanFollowUpPriorityCommand(
                    tenantId,
                    workItemId,
                    actor,
                    request.commandId,
                    request.claimId,
                    request.expectedOwnershipRevision,
                    request.expectedPriorityRevision,
                    priority,
                ),
            )
        val receipt = result.receipt
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(
            BrowserHumanFollowUpPriorityResponse(
                request.commandId,
                workItemId,
                request.claimId,
                request.expectedOwnershipRevision,
                request.expectedPriorityRevision,
                receipt.revision.value,
                receipt.command.priority.name,
                receipt.assignedAt,
                receipt.recordedAt,
            ),
        )
    }

    @ExceptionHandler(
        HumanFollowUpPriorityUnavailableException::class,
        InvalidHumanFollowUpPriorityException::class,
        HumanFollowUpPriorityNotFoundException::class,
        HumanFollowUpPriorityConflictException::class,
    )
    fun rejected(exception: RuntimeException): ProblemDetail {
        val (status, type) =
            when (exception) {
                is HumanFollowUpPriorityUnavailableException -> {
                    HttpStatus.SERVICE_UNAVAILABLE to "human-follow-up-priority-unavailable"
                }

                is HumanFollowUpPriorityNotFoundException -> {
                    HttpStatus.NOT_FOUND to "human-follow-up-priority-not-found"
                }

                is HumanFollowUpPriorityConflictException -> {
                    HttpStatus.CONFLICT to "human-follow-up-priority-conflict"
                }

                else -> {
                    HttpStatus.BAD_REQUEST to "invalid-human-follow-up-priority"
                }
            }
        return ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
        }
    }
}

/** No actor/authority fields: the verified session and server-side authority lookup supply them. */
data class BrowserHumanFollowUpPriorityRequest(
    val commandId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val expectedPriorityRevision: Long,
    val priority: String,
)

/** Bounded historical acknowledgement; excludes actor, authority evidence, case/run, provider identity and proof. */
data class BrowserHumanFollowUpPriorityResponse(
    val commandId: UUID,
    val workItemId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val expectedPriorityRevision: Long,
    val priorityRevision: Long,
    val priority: String,
    val assignedAt: Instant,
    val recordedAt: Instant,
)
