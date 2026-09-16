package org.ergon.controlplane.followup.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemNotFoundException
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps resolver-scoped follow-up lookup failures without exposing hidden work. */
@RestControllerAdvice(assignableTypes = [HumanFollowUpWorkItemController::class])
class HumanFollowUpWorkItemExceptionHandler {
    @ExceptionHandler(HumanFollowUpWorkItemNotFoundException::class)
    fun notFound(
        exception: HumanFollowUpWorkItemNotFoundException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "Human follow-up work not found")
            .apply {
                type = URI.create("urn:ergon:problem:human-follow-up-work-item-not-found")
                title = "Human follow-up work item not found"
                instance = URI.create(request.requestURI)
            }

    @ExceptionHandler(
        AuthenticatedHumanActorNotRegisteredException::class,
        UntrustedHumanIdentityIssuerException::class,
        InvalidAuthenticatedHumanIdentityException::class,
    )
    fun forbidden(
        exception: RuntimeException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:human-follow-up-access-forbidden")
            title = "Human follow-up access forbidden"
            instance = URI.create(request.requestURI)
        }
}
