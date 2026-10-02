package org.ergon.controlplane.followup.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommandConflictException
import org.ergon.controlplane.followup.application.HumanFollowUpOwnershipRevisionConflictException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpClaimCommandException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Stable, non-disclosing HTTP failures for revisioned claim commands. */
@RestControllerAdvice(assignableTypes = [HumanFollowUpWorkItemController::class])
class HumanFollowUpClaimCommandExceptionHandler {
    @ExceptionHandler(InvalidHumanFollowUpClaimCommandException::class)
    fun invalidClaimCommand(
        exception: InvalidHumanFollowUpClaimCommandException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:invalid-human-follow-up-claim-command")
            title = "Invalid human follow-up claim command"
            instance = URI.create(request.requestURI)
        }

    @ExceptionHandler(HumanFollowUpClaimCommandConflictException::class)
    fun claimCommandConflict(
        exception: HumanFollowUpClaimCommandConflictException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:human-follow-up-claim-command-conflict")
            title = "Human follow-up claim command conflict"
            instance = URI.create(request.requestURI)
        }

    @ExceptionHandler(HumanFollowUpOwnershipRevisionConflictException::class)
    fun ownershipRevisionConflict(
        exception: HumanFollowUpOwnershipRevisionConflictException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:human-follow-up-ownership-revision-conflict")
            title = "Human follow-up ownership revision conflict"
            instance = URI.create(request.requestURI)
        }
}
