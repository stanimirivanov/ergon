package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.RequesterInformationResponseCommand
import org.ergon.controlplane.followup.application.RequesterInformationResponseConflictException
import org.ergon.controlplane.followup.application.RequesterInformationResponseNotFoundException
import org.ergon.controlplane.followup.application.RequesterInformationResponseService
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.followup.domain.InvalidRequesterInformationAnswerException
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
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Requester-only access; BFF filters enforce the fixed OIDC audience, CSRF and no-store. */
@RestController
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class RequesterInformationResponseController(
    private val actors: AuthenticatedHumanActorResolver,
    private val responses: RequesterInformationResponseService,
) {
    /** First write is 201; exact currently authorized recovery is the original historical 200 receipt. */
    @PostMapping("/bff/v1/tenants/{tenantId}/requester-cases/{caseId}/information-requests/{requestId}/responses")
    fun record(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
        @PathVariable requestId: UUID,
        @RequestBody request: BrowserRequesterAnswer,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserRequesterAnswerReceipt> {
        val result =
            responses.record(
                RequesterInformationResponseCommand(
                    tenantId,
                    caseId,
                    requestId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    request.responseId,
                    request.answer,
                ),
            )
        val stored = result.stored
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(
            BrowserRequesterAnswerReceipt(
                stored.response.responseId.value,
                stored.response.requestId.value,
                stored.caseId.value,
                stored.response.answer.value,
                stored.response.respondedAt,
                stored.recordedAt,
            ),
        )
    }

    @ExceptionHandler(
        RequesterInformationResponseNotFoundException::class,
        RequesterInformationResponseConflictException::class,
        InvalidRequesterInformationAnswerException::class,
    )
    fun rejected(exception: RuntimeException): ProblemDetail {
        val (status, type) =
            when (exception) {
                is RequesterInformationResponseNotFoundException -> {
                    HttpStatus.NOT_FOUND to "requester-response-not-found"
                }

                is RequesterInformationResponseConflictException -> {
                    HttpStatus.CONFLICT to "requester-response-conflict"
                }

                else -> {
                    HttpStatus.BAD_REQUEST to "invalid-requester-response"
                }
            }
        return ProblemDetail.forStatusAndDetail(status, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
        }
    }
}

/** No actor, case, question or timestamp input outside the scoped route. Exact answer text is retained. */
data class BrowserRequesterAnswer(
    val responseId: UUID,
    val answer: String,
)

/** Historical acknowledgement without provider/actor identity, resolver context or continuing access. */
data class BrowserRequesterAnswerReceipt(
    val responseId: UUID,
    val requestId: UUID,
    val caseId: UUID,
    val answer: String,
    val respondedAt: Instant,
    val recordedAt: Instant,
)
