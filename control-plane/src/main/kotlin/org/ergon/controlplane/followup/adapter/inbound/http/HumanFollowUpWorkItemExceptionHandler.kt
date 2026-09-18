package org.ergon.controlplane.followup.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.followup.application.CurrentHumanFollowUpResolverAuthorityNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpAlreadyClaimedException
import org.ergon.controlplane.followup.application.HumanFollowUpClaimNotFoundException
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemNotFoundException
import org.ergon.controlplane.followup.application.InvalidHumanFollowUpWorkItemPageException
import org.ergon.controlplane.followup.application.InvalidResolverOwnedHumanFollowUpPageException
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps resolver-scoped follow-up query and claim failures without exposing hidden work. */
@RestControllerAdvice(assignableTypes = [HumanFollowUpWorkItemController::class])
class HumanFollowUpWorkItemExceptionHandler {
    @ExceptionHandler(HumanFollowUpAlreadyClaimedException::class)
    fun alreadyClaimed(
        exception: HumanFollowUpAlreadyClaimedException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:human-follow-up-already-claimed")
            title = "Human follow-up work item already claimed"
            instance = URI.create(request.requestURI)
        }

    @ExceptionHandler(InvalidHumanFollowUpWorkItemPageException::class)
    fun invalidPage(
        exception: InvalidHumanFollowUpWorkItemPageException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:invalid-human-follow-up-page")
            title = "Invalid human follow-up page"
            instance = URI.create(request.requestURI)
        }

    @ExceptionHandler(InvalidResolverOwnedHumanFollowUpPageException::class)
    fun invalidOwnedPage(
        exception: InvalidResolverOwnedHumanFollowUpPageException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:invalid-resolver-owned-human-follow-up-page")
            title = "Invalid resolver-owned human follow-up page"
            instance = URI.create(request.requestURI)
        }

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

    @ExceptionHandler(HumanFollowUpClaimNotFoundException::class)
    fun claimNotFound(
        exception: HumanFollowUpClaimNotFoundException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:human-follow-up-claim-not-found")
            title = "Human follow-up claim not found"
            instance = URI.create(request.requestURI)
        }

    @ExceptionHandler(CurrentHumanFollowUpResolverAuthorityNotFoundException::class)
    fun resolverAuthorityRequired(
        exception: CurrentHumanFollowUpResolverAuthorityNotFoundException,
        request: HttpServletRequest,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.message.orEmpty()).apply {
            type = URI.create("urn:ergon:problem:human-follow-up-resolver-authority-required")
            title = "Current resolver authority required"
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
