package org.ergon.controlplane.identity.adapter.inbound.security

import org.ergon.controlplane.identity.application.HumanActorAuthenticationService
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.util.UUID

/** Converts verified bearer claims into the tenant-scoped actor used by application commands. */
@Component
class AuthenticatedHumanActorResolver(
    private val service: HumanActorAuthenticationService,
    private val trust: HumanJwtTrust,
) {
    /**
     * Resolves [authentication] without accepting identity attributes from client input.
     *
     * @throws UntrustedHumanIdentityIssuerException when the verified issuer has no
     *   configured provider mapping.
     * @throws InvalidAuthenticatedHumanIdentityException when the token has no subject.
     * @throws org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
     *   when its provider and subject are not registered in [tenantId].
     */
    fun resolve(
        tenantId: UUID,
        authentication: JwtAuthenticationToken,
    ): StoredHumanActor {
        val token = authentication.token
        val identityProvider =
            trust.identityProviderFor(token.requiredIssuer().toURI())
                ?: throw UntrustedHumanIdentityIssuerException()
        return service.resolve(tenantId, identityProvider, token.requiredSubject())
    }

    private fun Jwt.requiredIssuer() = issuer ?: throw UntrustedHumanIdentityIssuerException()

    private fun Jwt.requiredSubject() = subject?.ifBlank { null } ?: throw InvalidAuthenticatedHumanIdentityException()
}

/** Signals that a verified token issuer is not mapped to an Ergon identity provider. */
class UntrustedHumanIdentityIssuerException : RuntimeException("token issuer is not trusted for human identity")

/** Signals that a verified token lacks the stable subject needed for identity binding. */
class InvalidAuthenticatedHumanIdentityException : RuntimeException("authenticated token has no subject")
