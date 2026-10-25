package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.InvalidRequesterInformationRequestException
import org.ergon.controlplane.followup.application.OwnedInformationRequestsService
import org.ergon.controlplane.followup.application.RequesterInformationRequestCommand
import org.ergon.controlplane.followup.application.RequesterInformationRequestConflictException
import org.ergon.controlplane.followup.application.RequesterInformationRequestNotFoundException
import org.ergon.controlplane.followup.application.RequesterInformationRequestService
import org.ergon.controlplane.followup.application.StoredRequesterInformationRequest
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.followup.domain.InvalidRequesterInformationQuestionException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Session-derived identity; existing BFF filters enforce command CSRF and no-store for both methods. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/information-requests")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRequesterInformationRequestController(
    private val actors: AuthenticatedHumanActorResolver,
    private val requests: RequesterInformationRequestService,
    private val history: OwnedInformationRequestsService,
) {
    /** Bounded history, including predecessor authors; access is evaluated anew, never from a retained receipt. */
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): BrowserOwnedInformationRequests {
        val page =
            history.list(
                tenantId,
                workItemId,
                actors
                    .resolve(tenantId, principal)
                    .actor.id.value,
            )
        return BrowserOwnedInformationRequests(page.items.map { it.toReceipt() }, page.truncated)
    }

    /** First write returns 201; exact currently authorized recovery returns the immutable 200 receipt. */
    @PostMapping
    fun record(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @RequestBody request: BrowserRequesterInformationRequest,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserRequesterInformationRequestReceipt> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        val result =
            requests.record(
                RequesterInformationRequestCommand(
                    tenantId,
                    workItemId,
                    actor,
                    request.requestId,
                    request.claimId,
                    request.expectedOwnershipRevision,
                    request.question,
                ),
            )
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(result.stored.toReceipt())
    }

    @ExceptionHandler(
        InvalidRequesterInformationQuestionException::class,
        InvalidRequesterInformationRequestException::class,
        RequesterInformationRequestNotFoundException::class,
        RequesterInformationRequestConflictException::class,
    )
    fun rejected(exception: RuntimeException): ProblemDetail {
        val (status, type) =
            when (exception) {
                is RequesterInformationRequestNotFoundException -> {
                    HttpStatus.NOT_FOUND to "requester-information-request-not-found"
                }

                is RequesterInformationRequestConflictException -> {
                    HttpStatus.CONFLICT to "requester-information-request-conflict"
                }

                else -> {
                    HttpStatus.BAD_REQUEST to "invalid-requester-information-request"
                }
            }
        return ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
        }
    }
}

/** No author, recipient or timestamp input: those coordinates belong to trusted server-side state. */
data class BrowserRequesterInformationRequest(
    val requestId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val question: String,
)

/** Historical acknowledgement only, without recipient/provider identity or continuing access rights. */
data class BrowserRequesterInformationRequestReceipt(
    val requestId: UUID,
    val caseId: UUID,
    val claimId: UUID,
    val ownershipRevision: Long,
    val authorActorId: UUID,
    val question: String,
    val requestedAt: Instant,
    val recordedAt: Instant,
)

data class BrowserOwnedInformationRequests(
    val items: List<BrowserRequesterInformationRequestReceipt>,
    val truncated: Boolean,
)

private fun StoredRequesterInformationRequest.toReceipt(): BrowserRequesterInformationRequestReceipt =
    BrowserRequesterInformationRequestReceipt(
        request.requestId.value,
        request.caseId.value,
        request.claimId.value,
        request.ownershipRevision.value,
        request.authorActorId.value,
        request.question.value,
        request.requestedAt,
        recordedAt,
    )
