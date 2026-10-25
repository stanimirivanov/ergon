package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.InvalidRequesterInformationRequestException
import org.ergon.controlplane.followup.application.RequesterInformationRequestCommand
import org.ergon.controlplane.followup.application.RequesterInformationRequestConflictException
import org.ergon.controlplane.followup.application.RequesterInformationRequestNotFoundException
import org.ergon.controlplane.followup.application.RequesterInformationRequestService
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.followup.domain.InvalidRequesterInformationQuestionException
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

/** Session-derived authorship; existing BFF filters enforce CSRF and no-store. No GET is exposed here. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/information-requests")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRequesterInformationRequestController(
    private val actors: AuthenticatedHumanActorResolver,
    private val requests: RequesterInformationRequestService,
) {
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
        val stored = result.stored
        val value = stored.request
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(
            BrowserRequesterInformationRequestReceipt(
                value.requestId.value,
                value.caseId.value,
                value.claimId.value,
                value.ownershipRevision.value,
                value.authorActorId.value,
                value.question.value,
                value.requestedAt,
                stored.recordedAt,
            ),
        )
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
