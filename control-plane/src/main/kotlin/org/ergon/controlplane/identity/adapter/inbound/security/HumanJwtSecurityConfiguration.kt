package org.ergon.controlplane.identity.adapter.inbound.security

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ergon.identity.domain.HumanActor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Immutable mapping from one trusted token issuer to Ergon's stable provider name.
 *
 * Configuration is either complete or absent. An absent mapping leaves protected
 * human-identity endpoints fail-closed while allowing deployments to start before an
 * identity provider is connected.
 *
 * @property issuer exact issuer URI accepted for human bearer identities, or `null`
 *   when human authentication is not configured.
 * @property identityProvider stable Ergon provider name assigned to that issuer, or
 *   `null` when human authentication is not configured.
 * @throws IllegalArgumentException when only one property is present or the provider
 *   name violates [HumanActor] constraints.
 */
data class HumanJwtTrust(
    val issuer: URI?,
    val identityProvider: String?,
) {
    init {
        require((issuer == null) == (identityProvider == null)) {
            "JWT issuer and human identity provider must be configured together"
        }
        identityProvider?.let {
            require(it.length <= HumanActor.MAX_IDENTITY_PROVIDER_LENGTH) {
                "human identity provider must be at most ${HumanActor.MAX_IDENTITY_PROVIDER_LENGTH} characters"
            }
            require(it.matches(Regex(HumanActor.IDENTITY_PROVIDER_PATTERN))) {
                "human identity provider must match ${HumanActor.IDENTITY_PROVIDER_PATTERN}"
            }
        }
    }

    /** Returns the provider only for the configured issuer's exact URI representation. */
    fun identityProviderFor(tokenIssuer: URI): String? = identityProvider?.takeIf { issuer == tokenIssuer }
}

/** Configures stateless bearer authentication for HTTP operations performed by human actors. */
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@Configuration(proxyBeanMethods = false)
class HumanJwtSecurityConfiguration {
    @Bean
    fun humanJwtAuthenticationEntryPoint(objectMapper: ObjectMapper) = HumanJwtAuthenticationEntryPoint(objectMapper)

    @Bean
    fun humanJwtTrust(
        @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") issuer: String,
        @Value("\${ergon.security.human-jwt.identity-provider:}") identityProvider: String,
    ): HumanJwtTrust {
        val normalizedIssuer = issuer.trim().takeIf(String::isNotEmpty)?.let(URI::create)
        val normalizedProvider = identityProvider.trim().takeIf(String::isNotEmpty)
        return HumanJwtTrust(normalizedIssuer, normalizedProvider)
    }

    @Bean
    fun jwtDecoder(trust: HumanJwtTrust): JwtDecoder =
        if (trust.issuer != null) {
            // Discovery is lazy so a temporarily unavailable IdP does not prevent startup.
            SupplierJwtDecoder { JwtDecoders.fromIssuerLocation(trust.issuer.toString()) }
        } else {
            JwtDecoder { throw BadJwtException("JWT issuer is not configured") }
        }

    @Bean
    fun humanJwtSecurityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        authenticationEntryPoint: HumanJwtAuthenticationEntryPoint,
    ): SecurityFilterChain {
        http
            // Ergon's HTTP API is stateless and authenticates protected requests by bearer token.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, CURRENT_HUMAN_ACTOR_PATH).authenticated()
                it.requestMatchers(HttpMethod.POST, APPROVAL_DECISION_PATH).authenticated()
                it.requestMatchers(HttpMethod.POST, RESOLUTION_RUN_RETRY_PATH).authenticated()
                it.requestMatchers(HttpMethod.POST, RESOLUTION_RUN_ESCALATION_PATH).authenticated()
                it.anyRequest().permitAll()
            }.oauth2ResourceServer { resourceServer ->
                resourceServer.authenticationEntryPoint(authenticationEntryPoint)
                resourceServer.jwt { jwt -> jwt.decoder(jwtDecoder) }
            }
        return http.build()
    }

    private companion object {
        const val APPROVAL_DECISION_PATH = "/api/v1/tenants/*/approval-requests/*/decision"
        const val CURRENT_HUMAN_ACTOR_PATH = "/api/v1/tenants/*/human-actor"
        const val RESOLUTION_RUN_RETRY_PATH = "/internal/v1/tenants/*/resolution-runs/*/retries"
        const val RESOLUTION_RUN_ESCALATION_PATH = "/internal/v1/tenants/*/resolution-runs/*/escalations"
    }
}

/** Writes stable RFC 9457 details for requests rejected before controller dispatch. */
class HumanJwtAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authenticationException: AuthenticationException,
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("WWW-Authenticate", "Bearer")
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to "urn:ergon:problem:human-authentication-required",
                "title" to "Human authentication required",
                "status" to HttpServletResponse.SC_UNAUTHORIZED,
                "detail" to "A valid bearer token is required",
                "instance" to request.requestURI,
            ),
        )
    }
}
