package org.ergon.controlplane.identity.adapter.inbound.http

import org.ergon.controlplane.identity.adapter.inbound.security.InvalidAuthenticatedHumanIdentityException
import org.ergon.controlplane.identity.adapter.inbound.security.UntrustedHumanIdentityIssuerException
import org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps authenticated-actor resolution failures without disclosing cross-tenant identities. */
@RestControllerAdvice(assignableTypes = [CurrentHumanActorController::class])
class CurrentHumanActorExceptionHandler {
    @ExceptionHandler(AuthenticatedHumanActorNotRegisteredException::class)
    fun actorNotRegistered(exception: AuthenticatedHumanActorNotRegisteredException): ProblemDetail =
        forbidden(
            "urn:ergon:problem:human-actor-not-registered",
            "Human actor not registered",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(UntrustedHumanIdentityIssuerException::class)
    fun untrustedIssuer(exception: UntrustedHumanIdentityIssuerException): ProblemDetail =
        forbidden(
            "urn:ergon:problem:untrusted-human-identity-issuer",
            "Untrusted human identity issuer",
            exception.message.orEmpty(),
        )

    @ExceptionHandler(InvalidAuthenticatedHumanIdentityException::class)
    fun invalidIdentity(exception: InvalidAuthenticatedHumanIdentityException): ProblemDetail =
        forbidden(
            "urn:ergon:problem:invalid-authenticated-human-identity",
            "Invalid authenticated human identity",
            exception.message.orEmpty(),
        )

    private fun forbidden(
        type: String,
        title: String,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail).apply {
            this.type = URI.create(type)
            this.title = title
        }
}
