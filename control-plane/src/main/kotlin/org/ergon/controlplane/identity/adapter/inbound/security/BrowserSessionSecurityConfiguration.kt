package org.ergon.controlplane.identity.adapter.inbound.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.savedrequest.NullRequestCache
import tools.jackson.databind.ObjectMapper
import java.net.URI

internal const val BROWSER_REGISTRATION_ID = "ergon-workbench"
internal const val BROWSER_RETURN_TO_SESSION_ATTRIBUTE = "ergon.browser.return-to"

/** Configures confidential OIDC login and server-side sessions for browser clients. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "true",
)
class BrowserSessionSecurityConfiguration(
    private val registrations: ClientRegistrationRepository,
    private val trust: HumanJwtTrust,
) {
    /** Separates requester cookies while retaining the common secure session settings. */
    @Bean
    @Order(Int.MAX_VALUE)
    fun cookieRules(audience: BrowserSessionAudience): WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            if (audience == BrowserSessionAudience.REQUESTER) {
                factory.settings.session.cookie.name = "__Host-Http-Ergon-Requester-Session"
            }
        }

    @Bean
    fun browserSessionEntryPoint(objectMapper: ObjectMapper) = BrowserSessionAuthenticationEntryPoint(objectMapper)

    @Bean
    fun browserSessionAccessDeniedHandler(
        objectMapper: ObjectMapper,
        authenticationEntryPoint: BrowserSessionAuthenticationEntryPoint,
    ) = BrowserSessionAccessDeniedHandler(objectMapper, authenticationEntryPoint)

    @Bean
    @Order(1)
    fun browserSessionSecurityFilterChain(
        http: HttpSecurity,
        audience: BrowserSessionAudience,
        authenticationEntryPoint: BrowserSessionAuthenticationEntryPoint,
        accessDeniedHandler: BrowserSessionAccessDeniedHandler,
    ): SecurityFilterChain {
        requiredBrowserRegistration(registrations, trust, audience)

        val authorizationRequests =
            DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization").apply {
                // A confidential BFF still binds every authorization code to its initiating browser flow.
                setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
            }

        // A requester deployment must not fall through to the legacy internal API chain.
        if (audience == BrowserSessionAudience.WORKBENCH) {
            http.securityMatcher("/bff/**", "/oauth2/**", "/login/oauth2/**")
        } else {
            // Local logout is a separately planned contract, not an implicit filter endpoint.
            http.logout { it.disable() }
        }
        http
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        HttpMethod.GET,
                        "/bff/login",
                        "/oauth2/authorization/${audience.registrationId}",
                        "/login/oauth2/code/${audience.registrationId}",
                    ).permitAll()
                it.requestMatchers("/oauth2/**", "/login/oauth2/**").denyAll()
                if (audience == BrowserSessionAudience.REQUESTER) {
                    it
                        .requestMatchers(
                            HttpMethod.GET,
                            "/bff/v1/csrf",
                            "/bff/v1/tenants/*/session",
                            "/bff/v1/tenants/*/requester-cases/*/progress",
                        ).access { auth, _ ->
                            val token = auth.get() as? OAuth2AuthenticationToken
                            AuthorizationDecision(token?.authorizedClientRegistrationId == audience.registrationId)
                        }
                    it.anyRequest().denyAll()
                } else {
                    it.requestMatchers("/bff/v1/tenants/*/requester-cases/**").denyAll()
                    it.anyRequest().authenticated()
                }
            }.requestCache {
                // API probes must not become post-login redirect targets.
                it.requestCache(NullRequestCache())
            }.exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }.oauth2Login { login ->
                if (audience == BrowserSessionAudience.REQUESTER) {
                    login.loginPage("/bff/login")
                    login.failureHandler { request, response, exception ->
                        authenticationEntryPoint.commence(request, response, exception)
                    }
                }
                login.authorizationEndpoint { endpoint ->
                    endpoint.authorizationRequestResolver(
                        AudienceAuthorizationRequestResolver(authorizationRequests, audience.registrationId),
                    )
                }
                login.redirectionEndpoint { it.baseUri("/login/oauth2/code/${audience.registrationId}") }
                login.successHandler(BrowserAuthenticationSuccessHandler())
            }
        return http.build()
    }
}

/** Produces stable browser problems for CSRF rejection without masking an expired session. */
class BrowserSessionAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
    private val authenticationEntryPoint: BrowserSessionAuthenticationEntryPoint,
) : AccessDeniedHandler {
    private val defaultHandler = AccessDeniedHandlerImpl()

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        if (accessDeniedException !is CsrfException) {
            defaultHandler.handle(request, response, accessDeniedException)
            return
        }

        val authentication = SecurityContextHolder.getContext().authentication
        val requiresAuthentication =
            authentication == null ||
                authentication is AnonymousAuthenticationToken ||
                !authentication.isAuthenticated
        if (requiresAuthentication) {
            authenticationEntryPoint.commence(
                request,
                response,
                InsufficientAuthenticationException("An authenticated browser session is required"),
            )
            return
        }

        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to "urn:ergon:problem:invalid-browser-csrf-token",
                "title" to "Invalid browser CSRF token",
                "status" to HttpServletResponse.SC_FORBIDDEN,
                "detail" to "A valid browser CSRF token is required",
                "instance" to URI.create(request.requestURI),
            ),
        )
    }
}

/** Writes a stable problem document instead of redirecting unauthenticated API requests. */
class BrowserSessionAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authenticationException: AuthenticationException,
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to "urn:ergon:problem:browser-authentication-required",
                "title" to "Browser authentication required",
                "status" to HttpServletResponse.SC_UNAUTHORIZED,
                "detail" to "An authenticated browser session is required",
                "instance" to request.requestURI,
                "signInPath" to "/bff/login",
            ),
        )
    }
}

/** Restores only a validated local tenant path after successful OIDC login. */
internal class BrowserAuthenticationSuccessHandler : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val session = request.getSession(false)
        val returnTo = session?.getAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE) as? String
        session?.removeAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE)
        response.sendRedirect(returnTo ?: "/")
    }
}

/** Fails startup unless this deployment's fixed confidential registration matches the trusted issuer. */
internal fun requiredBrowserRegistration(
    registrations: ClientRegistrationRepository,
    trust: HumanJwtTrust,
    audience: BrowserSessionAudience,
): ClientRegistration =
    requireNotNull(registrations.findByRegistrationId(audience.registrationId)) {
        "OAuth client registration '${audience.registrationId}' is required when browser sessions are enabled"
    }.also { it.validateForBrowserSession(trust) }

private fun ClientRegistration.validateForBrowserSession(trust: HumanJwtTrust) {
    require(authorizationGrantType == AuthorizationGrantType.AUTHORIZATION_CODE) {
        "browser session OAuth client must use authorization_code"
    }
    require(clientAuthenticationMethod == ClientAuthenticationMethod.CLIENT_SECRET_BASIC) {
        "browser session OAuth client must use client_secret_basic"
    }
    require(clientSecret.isNotBlank()) { "browser session OAuth client secret must not be blank" }
    require("openid" in scopes) { "browser session OAuth client must request the openid scope" }

    val trustedIssuer =
        requireNotNull(trust.issuer) {
            "human JWT issuer must be configured when browser sessions are enabled"
        }
    require(providerDetails.issuerUri == trustedIssuer.toString()) {
        "browser OIDC issuer must match the trusted human JWT issuer"
    }
}
