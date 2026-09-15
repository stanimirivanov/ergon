package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.ergon.controlplane.resolution.application.CurrentResolutionRecoveryAuthorityNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps failed retry authentication and resolver-authority checks to stable problem documents. */
@RestControllerAdvice(assignableTypes = [ResolutionRunRetryController::class])
class ResolutionRunRetryAuthorizationExceptionHandler {
    @ExceptionHandler(CurrentResolutionRecoveryAuthorityNotFoundException::class)
    fun authorityNotFound(exception: CurrentResolutionRecoveryAuthorityNotFoundException): ProblemDetail =
        problem(
            "current-resolution-recovery-authority-not-found",
            "Current recovery authority not found",
            exception,
        )

    @ExceptionHandler(
        AuthenticatedHumanActorNotRegisteredException::class,
        UntrustedHumanIdentityIssuerException::class,
        InvalidAuthenticatedHumanIdentityException::class,
    )
    fun invalidActor(exception: RuntimeException): ProblemDetail =
        problem("invalid-authenticated-human-actor", "Invalid authenticated human actor", exception)

    private fun problem(
        type: String,
        title: String,
        exception: Exception,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.message.orEmpty()).apply {
            this.type = URI.create("urn:ergon:problem:$type")
            this.title = title
        }
}
