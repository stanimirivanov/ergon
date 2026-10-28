package org.ergon.controlplane.identity.adapter.inbound.http

import jakarta.servlet.http.HttpServletRequest
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.identity.adapter.inbound.security.BROWSER_RETURN_TO_SESSION_ATTRIBUTE
import org.ergon.controlplane.identity.adapter.inbound.security.BrowserSessionAudience
import org.ergon.controlplane.identity.adapter.inbound.security.InvalidBrowserOidcIdentityException
import org.ergon.controlplane.identity.application.StoredHumanActor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
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
    private val audience: BrowserSessionAudience,
) {
    /** Returns the opaque session-bound token and header name required for browser mutations. */
    @GetMapping("/bff/v1/csrf")
    fun csrf(csrfToken: CsrfToken) =
        BrowserCsrfTokenResponse(
            headerName = csrfToken.headerName,
            token = csrfToken.token,
        )

    /** Resolves the verified OIDC session to one actor without returning its provider subject. */
    @GetMapping("/bff/v1/tenants/{tenantId}/session")
    fun currentSession(
        @PathVariable tenantId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): BrowserActorSessionResponse = actors.resolve(tenantId, principal).toBrowserSessionResponse()

    /** Starts this deployment's login, preserving only a canonical tenant path in the server session. */
    @GetMapping("/bff/login")
    fun login(
        @RequestParam returnTo: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val validatedReturnTo = validateReturnTo(returnTo)
        request.session.setAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE, validatedReturnTo)
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .location(URI.create("/oauth2/authorization/${audience.registrationId}"))
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

/** Session-bound anti-forgery value that browser code must keep in memory only. */
data class BrowserCsrfTokenResponse(
    val headerName: String,
    val token: String,
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
    /** Requester writes cannot dispatch without browser OIDC configuration. */
    @PostMapping(
        "/v1/tenants/{tenantId}/requester-cases",
        "/v1/tenants/{tenantId}/requester-cases/*/information-requests/*/responses",
    )
    fun requesterWriteUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Reports that the login entry point is intentionally unavailable. */
    @GetMapping("/login")
    fun loginUnavailable(): ProblemDetail = unavailableProblem()

    /** Fails session-protected reads closed without browser OIDC configuration. */
    @GetMapping(
        "/v1/tenants/{tenantId}/session",
        "/v1/tenants/{tenantId}/notifications",
        "/v1/tenants/{tenantId}/requester-cases/*/progress",
        "/v1/tenants/{tenantId}/requester-cases/*/information-requests",
    )
    fun sessionUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Reports that mutation protection is unavailable without browser OIDC configuration. */
    @GetMapping("/v1/csrf")
    fun csrfUnavailable(): ProblemDetail = unavailableProblem()

    /** Reports that the resolver inbox cannot be read without browser OIDC configuration. */
    @GetMapping("/v1/tenants/{tenantId}/human-follow-ups")
    fun humanFollowUpInboxUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Reports that browser-owned work cannot be read without browser OIDC configuration. */
    @GetMapping("/v1/tenants/{tenantId}/human-follow-ups/owned")
    fun resolverOwnedHumanFollowUpUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Protected case context and notes share the same disabled-browser policy. */
    @GetMapping(
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/notes",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/information-requests",
    )
    fun resolverFollowUpContextUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
        @Suppress("UNUSED_PARAMETER") @PathVariable workItemId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Reports that browser claiming is unavailable without browser OIDC configuration. */
    @PostMapping("/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims")
    fun humanFollowUpClaimUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
        @Suppress("UNUSED_PARAMETER") @PathVariable workItemId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** No follow-up command is dispatched without browser OIDC configuration. */
    @PostMapping(
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claim-commands",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/notes",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/handovers",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/completions",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/priority-assignments",
        "/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/information-requests",
    )
    fun humanFollowUpCommandUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
        @Suppress("UNUSED_PARAMETER") @PathVariable workItemId: UUID,
    ): ProblemDetail = unavailableProblem()

    /** Reports that release requires browser OIDC configuration. */
    @PostMapping("/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/claims/{claimId}/release")
    fun humanFollowUpReleaseUnavailable(
        @Suppress("UNUSED_PARAMETER") @PathVariable tenantId: UUID,
        @Suppress("UNUSED_PARAMETER") @PathVariable workItemId: UUID,
        @Suppress("UNUSED_PARAMETER") @PathVariable claimId: UUID,
    ): ProblemDetail = unavailableProblem()
}

private fun unavailableProblem(): ProblemDetail =
    ProblemDetail
        .forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Browser authentication is not configured",
        ).apply {
            type = URI.create("urn:ergon:problem:browser-authentication-unavailable")
            title = "Browser authentication unavailable"
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
