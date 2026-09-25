package org.ergon.controlplane.followup.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.followup.application.CurrentHumanFollowUpResolverAuthorityNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpAlreadyClaimedException
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemNotFoundException
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps browser claim failures without exposing authority evidence or provider bindings. */
@RestControllerAdvice(assignableTypes = [BrowserHumanFollowUpClaimController::class])
class BrowserHumanFollowUpClaimExceptionHandler {
    @ExceptionHandler(HumanFollowUpAlreadyClaimedException::class)
    fun alreadyClaimed(
        exception: HumanFollowUpAlreadyClaimedException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.CONFLICT,
            "urn:ergon:problem:human-follow-up-already-claimed",
            "Human follow-up work item already claimed",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(HumanFollowUpWorkItemNotFoundException::class)
    fun workItemNotFound(
        exception: HumanFollowUpWorkItemNotFoundException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "urn:ergon:problem:human-follow-up-work-item-not-found",
            "Human follow-up work item not found",
            exception.message.orEmpty(),
            request,
        )

    @ExceptionHandler(CurrentHumanFollowUpResolverAuthorityNotFoundException::class)
    fun resolverAuthorityRequired(
        exception: CurrentHumanFollowUpResolverAuthorityNotFoundException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:human-follow-up-resolver-authority-required",
            "Current resolver authority required",
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
