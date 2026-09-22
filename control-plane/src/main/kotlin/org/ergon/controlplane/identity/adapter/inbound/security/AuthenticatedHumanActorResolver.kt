package org.ergon.controlplane.identity.adapter.inbound.security

import org.ergon.controlplane.identity.application.HumanActorAuthenticationService
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

/** Converts verified protocol identities into tenant-scoped actors used by application commands. */
@Component
class AuthenticatedHumanActorResolver(
    private val service: HumanActorAuthenticationService,
    private val trust: HumanJwtTrust,
) {
    /**
     * Resolves a protocol-verified OIDC principal without accepting browser-supplied identity attributes.
     *
     * @throws InvalidBrowserOidcIdentityException when the verified principal has no issuer or subject.
     * @throws UntrustedHumanIdentityIssuerException when its verified issuer has no provider mapping.
     * @throws org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
     *   when its provider and subject are not registered in [tenantId].
     */
    fun resolve(
        tenantId: UUID,
        principal: OidcUser,
    ): StoredHumanActor {
        val issuer = principal.issuer ?: throw InvalidBrowserOidcIdentityException()
        val subject = principal.subject?.ifBlank { null } ?: throw InvalidBrowserOidcIdentityException()
        return resolve(tenantId, issuer.toURI(), subject)
    }

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
    ): StoredHumanActor =
        resolve(
            tenantId,
            authentication.token.requiredIssuer().toURI(),
            authentication.token.requiredSubject(),
        )

    /**
     * Resolves a protocol-verified issuer and opaque subject inside [tenantId].
     *
     * Callers must supply claims only after their authentication mechanism has
     * verified token integrity, issuer, time constraints, and OIDC state/nonce as
     * applicable. Client-controlled identity attributes are not accepted.
     *
     * @throws UntrustedHumanIdentityIssuerException when [verifiedIssuer] has no
     *   configured provider mapping.
     * @throws InvalidAuthenticatedHumanIdentityException when [verifiedSubject] is blank.
     * @throws org.ergon.controlplane.identity.application.AuthenticatedHumanActorNotRegisteredException
     *   when the mapped provider and subject are not registered in [tenantId].
     */
    fun resolve(
        tenantId: UUID,
        verifiedIssuer: URI,
        verifiedSubject: String,
    ): StoredHumanActor {
        val identityProvider =
            trust.identityProviderFor(verifiedIssuer)
                ?: throw UntrustedHumanIdentityIssuerException()
        val subject = verifiedSubject.ifBlank { throw InvalidAuthenticatedHumanIdentityException() }
        return service.resolve(tenantId, identityProvider, subject)
    }

    private fun Jwt.requiredIssuer() = issuer ?: throw UntrustedHumanIdentityIssuerException()

    private fun Jwt.requiredSubject() = subject?.ifBlank { null } ?: throw InvalidAuthenticatedHumanIdentityException()
}

/** Signals that a verified token issuer is not mapped to an Ergon identity provider. */
class UntrustedHumanIdentityIssuerException : RuntimeException("token issuer is not trusted for human identity")

/** Signals that a verified token lacks the stable subject needed for identity binding. */
class InvalidAuthenticatedHumanIdentityException : RuntimeException("authenticated token has no subject")

/** Signals that a verified browser principal lacks claims required for tenant actor resolution. */
class InvalidBrowserOidcIdentityException : RuntimeException("verified OIDC identity is incomplete")
