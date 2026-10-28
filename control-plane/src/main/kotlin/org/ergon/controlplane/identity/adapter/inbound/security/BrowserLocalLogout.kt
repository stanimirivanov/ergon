package org.ergon.controlplane.identity.adapter.inbound.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.logout.LogoutHandler
import org.springframework.security.web.util.matcher.RequestMatcher

internal const val BROWSER_LOGOUT_PATH = "/bff/logout"

/**
 * LogoutFilter runs before route authorization, so it must independently reject
 * non-OIDC and foreign-client identities. No actor/role lookup is needed to give
 * up a local session. CSRF is enforced by the preceding Spring filter.
 */
internal class BrowserLocalLogoutMatcher(
    private val audience: BrowserSessionAudience,
) : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean {
        if (request.method != "POST" || request.requestURI.removePrefix(request.contextPath) != BROWSER_LOGOUT_PATH) {
            return false
        }
        val authentication = SecurityContextHolder.getContext().authentication as? OAuth2AuthenticationToken
        return authentication?.isAuthenticated == true &&
            authentication.principal is OidcUser &&
            authentication.authorizedClientRegistrationId == audience.registrationId &&
            request.getSession(false) != null
    }
}

/**
 * Expire only this deployment's cookie. Explicit security attributes are required
 * for __Host-Http cookies even when a local reverse proxy forwards HTTP internally.
 * Composition supplies the selected audience and server cookie configuration;
 * neither request cookies nor uninitialized servlet metadata select it.
 * No broad Clear-Site-Data is emitted.
 */
internal fun browserLogoutCookie(
    audience: BrowserSessionAudience,
    workbenchCookieName: String,
): LogoutHandler {
    val name =
        when (audience) {
            BrowserSessionAudience.REQUESTER -> audience.sessionCookieName
            BrowserSessionAudience.WORKBENCH -> workbenchCookieName
        }
    // Resolve/validate trusted configuration at startup, not during destructive cleanup.
    val cookie =
        ResponseCookie
            .from(name, "")
            .path("/")
            .maxAge(0)
            .secure(true)
            .httpOnly(true)
            .sameSite("Lax")
            .build()
            .toString()
    return LogoutHandler { _, response, _ -> response.addHeader(HttpHeaders.SET_COOKIE, cookie) }
}

/** Local retirement only; no redirect or assertion about provider logout or already accepted work. */
internal fun browserLogoutSucceeded(response: HttpServletResponse) {
    response.status = HttpServletResponse.SC_NO_CONTENT
    response.setHeader("Cache-Control", "no-store")
}
