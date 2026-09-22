package org.ergon.controlplane.identity.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.adapter.inbound.security.BROWSER_REGISTRATION_ID
import org.ergon.controlplane.identity.adapter.inbound.security.BROWSER_RETURN_TO_SESSION_ATTRIBUTE
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Browser-facing session adapter that never returns OAuth or OIDC tokens. */
@RestController
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "true",
)
class BrowserSessionController(
    private val actors: AuthenticatedHumanActorResolver,
) {
    /** Resolves the verified OIDC session to one actor without returning its provider subject. */
    @GetMapping("/bff/v1/tenants/{tenantId}/session")
    fun currentSession(
        @PathVariable tenantId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): BrowserActorSessionResponse = actors.resolve(tenantId, principal).toBrowserSessionResponse()

    /** Starts login only for a canonical tenant workbench path kept in the server session. */
    @GetMapping("/bff/login")
    fun login(
        @RequestParam returnTo: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val validatedReturnTo = validateReturnTo(returnTo)
        request.session.setAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE, validatedReturnTo)
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .location(URI.create("/oauth2/authorization/$BROWSER_REGISTRATION_ID"))
            .build()
    }
}

/** Stable browser representation of an authenticated tenant actor. */
data class BrowserActorSessionResponse(
    val actorId: UUID,
    val identityProvider: String,
    val registeredAt: Instant,
    val recordedAt: Instant,
)

/** Exposes stable fail-closed responses for browser routes when OIDC is not configured. */
@RestController
@RequestMapping("/bff")
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class BrowserSessionUnavailableController {
    /** Reports that the login entry point is intentionally unavailable. */
    @GetMapping("/login")
    fun loginUnavailable(): ProblemDetail = unavailableProblem()

    /** Reports that no tenant session can be resolved without browser OIDC configuration. */
    @GetMapping("/v1/tenants/{tenantId}/session")
    fun sessionUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Reports that the resolver inbox cannot be read without browser OIDC configuration. */
    @GetMapping("/v1/tenants/{tenantId}/human-follow-ups")
    fun humanFollowUpInboxUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    private fun unavailableProblem(): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Browser authentication is not configured",
            ).apply {
                type = URI.create("urn:ergon:problem:browser-authentication-unavailable")
                title = "Browser authentication unavailable"
            }
}

private fun validateReturnTo(returnTo: String): String {
    val prefix = "/tenants/"
    if (!returnTo.startsWith(prefix)) {
        invalidReturnPath()
    }
    val value = returnTo.removePrefix(prefix)
    val tenantId = runCatching { UUID.fromString(value) }.getOrElse { invalidReturnPath() }
    if (!tenantId.toString().equals(value, ignoreCase = true)) {
        invalidReturnPath()
    }
    return "$prefix$tenantId"
}

private fun invalidReturnPath(): Nothing = throw InvalidBrowserReturnPathException()

private fun StoredHumanActor.toBrowserSessionResponse() =
    BrowserActorSessionResponse(
        actorId = actor.id.value,
        identityProvider = actor.identityProvider,
        registeredAt = registeredAt,
        recordedAt = recordedAt,
    )

class InvalidBrowserReturnPathException : RuntimeException("returnTo must be a canonical tenant workbench path")
