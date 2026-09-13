package org.ergon.controlplane.identity.adapter.inbound.http

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.ergon.controlplane.identity.adapter.inbound.security.HumanJwtTrust
import org.ergon.controlplane.identity.application.HumanActorAuthenticationService
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Resolves the verified bearer identity to its actor in the requested tenant. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/human-actor")
@SecurityRequirement(name = "bearerAuth")
class CurrentHumanActorController(
    private val service: HumanActorAuthenticationService,
    private val trust: HumanJwtTrust,
) {
    /**
     * Returns the current actor without accepting caller-controlled identity attributes.
     *
     * @throws UntrustedHumanIdentityIssuerException when the verified token issuer has no
     *   configured actor-provider mapping.
     * @throws InvalidAuthenticatedHumanIdentityException when the verified token has no subject.
     */
    @GetMapping
    fun currentActor(
        @PathVariable tenantId: UUID,
        authentication: JwtAuthenticationToken,
    ): HumanActorResponse {
        val token = authentication.token
        val issuer = token.requiredIssuer()
        val identityProvider =
            trust.identityProviderFor(issuer.toURI()) ?: throw UntrustedHumanIdentityIssuerException()
        val subject = token.requiredSubject()
        return service.resolve(tenantId, identityProvider, subject).toResponse()
    }

    private fun Jwt.requiredIssuer() = issuer ?: throw UntrustedHumanIdentityIssuerException()

    private fun Jwt.requiredSubject() = subject?.ifBlank { null } ?: throw InvalidAuthenticatedHumanIdentityException()
}

/** Signals that a verified token issuer is not mapped to an Ergon identity provider. */
class UntrustedHumanIdentityIssuerException : RuntimeException("token issuer is not trusted for human identity")

/** Signals that a verified token lacks the stable subject needed for identity binding. */
class InvalidAuthenticatedHumanIdentityException : RuntimeException("authenticated token has no subject")
