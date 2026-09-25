package org.ergon.controlplane.followup.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpQueueException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpWorkItemPageException
import org.ergon.controlplane.followup.application.InvalidResolverOwnedHumanFollowUpPageException
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps browser follow-up query failures without exposing provider bindings or hidden work. */
@RestControllerAdvice(assignableTypes = [BrowserHumanFollowUpInboxController::class])
class BrowserHumanFollowUpInboxExceptionHandler {
    @ExceptionHandler(InvalidHumanFollowUpWorkItemPageException::class)
    fun invalidPage(
        exception: InvalidHumanFollowUpWorkItemPageException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "urn:ergon:problem:invalid-human-follow-up-page",
            "Invalid human follow-up page",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(InvalidResolverOwnedHumanFollowUpPageException::class)
    fun invalidOwnedPage(
        exception: InvalidResolverOwnedHumanFollowUpPageException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "urn:ergon:problem:invalid-resolver-owned-human-follow-up-page",
            "Invalid resolver-owned human follow-up page",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(InvalidHumanFollowUpQueueException::class)
    fun invalidQueue(
        exception: InvalidHumanFollowUpQueueException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "urn:ergon:problem:invalid-human-follow-up-queue",
            "Invalid human follow-up queue",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(AuthenticatedHumanActorNotRegisteredException::class)
    fun actorNotRegistered(
        exception: AuthenticatedHumanActorNotRegisteredException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:human-actor-not-registered",
            "Human actor not registered",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(UntrustedHumanIdentityIssuerException::class)
    fun untrustedIssuer(
        exception: UntrustedHumanIdentityIssuerException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:untrusted-human-identity-issuer",
            "Untrusted human identity issuer",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(InvalidAuthenticatedHumanIdentityException::class, InvalidBrowserOidcIdentityException::class)
    fun invalidIdentity(
        exception: RuntimeException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:invalid-authenticated-human-identity",
            "Invalid authenticated human identity",
            exception.message.orEmpty(),
            request,
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            instance = URI.create(request.requestURI)
        }
}
