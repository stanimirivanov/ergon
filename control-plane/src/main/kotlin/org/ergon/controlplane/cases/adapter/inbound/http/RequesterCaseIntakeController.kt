package org.ergon.controlplane.cases.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.ergon.controlplane.cases.application.InvalidRequesterCaseIntakeException
import org.ergon.controlplane.cases.application.RequesterCaseIntakeCommand
import org.ergon.controlplane.cases.application.RequesterCaseIntakeConflictException
import org.ergon.controlplane.cases.application.RequesterCaseIntakeService
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Requester-only intake; security filters enforce the fixed client, session CSRF and no-store. */
@RestController
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["audience"], havingValue = "requester")
class RequesterCaseIntakeController(
    private val actors: AuthenticatedHumanActorResolver,
    private val intake: RequesterCaseIntakeService,
) {
    /** Creates once (201) or recovers the original normalized opening (200), never current lifecycle. */
    @PostMapping("/bff/v1/tenants/{tenantId}/requester-cases")
    fun open(
        @PathVariable tenantId: UUID,
        @RequestBody request: BrowserRequesterCaseIntake,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserRequesterCaseIntakeReceipt> {
        val result =
            intake.open(
                RequesterCaseIntakeCommand(
                    tenantId,
                    request.caseId,
                    actors
                        .resolve(tenantId, principal)
                        .actor.id.value,
                    request.goal,
                    request.initialObservation,
                ),
            )
        val receipt = result.receipt
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(
            BrowserRequesterCaseIntakeReceipt(
                receipt.caseId,
                receipt.goal,
                receipt.initialObservation,
                receipt.openedAt,
                receipt.recordedAt,
            ),
        )
    }

    @ExceptionHandler(RequesterCaseIntakeConflictException::class)
    fun conflict(): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "requester-case-intake-conflict",
            "Requester intake intent conflicts",
        )

    @ExceptionHandler(
        InvalidRequesterCaseIntakeException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "invalid-requester-case-intake",
            "Invalid requester case intake",
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, title).apply {
            this.type = URI.create("urn:ergon:problem:$type")
            this.title = title
        }
}

/** Unknown members are ignored, including actor/authority hints; only the verified session chooses ownership. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BrowserRequesterCaseIntake(
    val caseId: UUID,
    val goal: String,
    val initialObservation: String,
)

/** Original normalized input and opening times; the caller must separately reacquire current progress. */
data class BrowserRequesterCaseIntakeReceipt(
    val caseId: UUID,
    val goal: String,
    val initialObservation: String,
    val openedAt: Instant,
    val recordedAt: Instant,
)
