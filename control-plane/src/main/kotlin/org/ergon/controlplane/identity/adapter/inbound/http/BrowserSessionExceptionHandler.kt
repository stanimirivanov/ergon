package org.ergon.controlplane.identity.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps browser-session failures without disclosing cross-tenant identity bindings. */
@RestControllerAdvice(assignableTypes = [BrowserSessionController::class])
class BrowserSessionExceptionHandler {
    @ExceptionHandler(AuthenticatedHumanActorNotRegisteredException::class)
    fun actorNotRegistered(exception: AuthenticatedHumanActorNotRegisteredException): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:human-actor-not-registered",
            "Human actor not registered",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(UntrustedHumanIdentityIssuerException::class)
    fun untrustedIssuer(exception: UntrustedHumanIdentityIssuerException): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:untrusted-human-identity-issuer",
            "Untrusted human identity issuer",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(InvalidAuthenticatedHumanIdentityException::class, InvalidBrowserOidcIdentityException::class)
    fun invalidIdentity(exception: RuntimeException): ProblemDetail =
        problem(
            HttpStatus.FORBIDDEN,
            "urn:ergon:problem:invalid-authenticated-human-identity",
            "Invalid authenticated human identity",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(InvalidBrowserReturnPathException::class)
    fun invalidReturnPath(exception: InvalidBrowserReturnPathException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "urn:ergon:problem:invalid-browser-return-path",
            "Invalid browser return path",
            exception.message.orEmpty(),
        )

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
        }
}
