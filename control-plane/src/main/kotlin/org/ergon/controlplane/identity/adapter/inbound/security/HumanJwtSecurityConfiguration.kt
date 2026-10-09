package org.ergon.controlplane.identity.adapter.inbound.security

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityScheme
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ergon.controlplane.resolution.application.MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH
import org.ergon.identity.domain.HumanActor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

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
    @Order(2)
    fun humanJwtSecurityFilterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        trust: HumanJwtTrust,
        authenticationEntryPoint: HumanJwtAuthenticationEntryPoint,
    ): SecurityFilterChain {
        http
            // Ergon's HTTP API is stateless and authenticates protected requests by bearer token.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.POST, RUN_SUPERVISOR_ASSIGNMENTS_PATH)
                    .access(runSupervisorAssignmentAccess(trust))
                // A servlet-path variant must never fall through to the legacy
                // permit-all internal-route default.
                it
                    .requestMatchers(
                        HttpMethod.POST,
                        "/internal/v1/tenants/*/resolution-runs/*/supervisor-assignments/**",
                    ).denyAll()
                it
                    .requestMatchers(HttpMethod.POST, "/internal/v1/tenants/*/resolution-runs/*/supervisor-assignments")
                    .denyAll()
                it.requestMatchers(HttpMethod.GET, CURRENT_HUMAN_ACTOR_PATH).authenticated()
                it.requestMatchers(HttpMethod.GET, HUMAN_FOLLOW_UP_COLLECTION_PATH).authenticated()
                it.requestMatchers(HttpMethod.GET, HUMAN_FOLLOW_UP_PATH).authenticated()
                it.requestMatchers(HttpMethod.GET, HUMAN_FOLLOW_UP_CLAIM_PATH).authenticated()
                it.requestMatchers(HttpMethod.POST, HUMAN_FOLLOW_UP_CLAIMS_PATH).authenticated()
                it.requestMatchers(HttpMethod.POST, HUMAN_FOLLOW_UP_CLAIM_COMMANDS_PATH).authenticated()
                it.requestMatchers(HttpMethod.POST, HUMAN_FOLLOW_UP_RELEASE_PATH).authenticated()
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
        const val HUMAN_FOLLOW_UP_COLLECTION_PATH = "/internal/v1/tenants/*/human-follow-ups"
        const val HUMAN_FOLLOW_UP_PATH = "/internal/v1/tenants/*/human-follow-ups/*"
        const val HUMAN_FOLLOW_UP_CLAIMS_PATH = "/internal/v1/tenants/*/human-follow-ups/*/claims"
        const val HUMAN_FOLLOW_UP_CLAIM_PATH = "/internal/v1/tenants/*/human-follow-ups/*/claims/*"
        const val HUMAN_FOLLOW_UP_CLAIM_COMMANDS_PATH = "/internal/v1/tenants/*/human-follow-ups/*/claim-commands"
        const val HUMAN_FOLLOW_UP_RELEASE_PATH = "/internal/v1/tenants/*/human-follow-ups/*/claims/*/release"
        const val RESOLUTION_RUN_RETRY_PATH = "/internal/v1/tenants/*/resolution-runs/*/retries"
        const val RESOLUTION_RUN_ESCALATION_PATH = "/internal/v1/tenants/*/resolution-runs/*/escalations"
        const val RUN_SUPERVISOR_ASSIGNMENTS_PATH =
            "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/supervisor-assignments"
    }
}

private const val RUN_SUPERVISION_ASSIGN_SCOPE = "SCOPE_ergon.run-supervision.assign"
private const val RUN_SUPERVISION_AUDIENCE = "ergon-run-supervision"
private const val RUN_SUPERVISION_TENANT_CLAIM = "ergon_tenant_id"

/**
 * Requires a trusted machine token scoped to the exact path tenant before assignment dispatch.
 *
 * A missing or noncanonical tenant claim, nonmatching path, or unconfigured
 * issuer fails closed. The tenant claim must be a string UUID, not a human-selected header.
 */
private fun runSupervisorAssignmentAccess(trust: HumanJwtTrust): AuthorizationManager<RequestAuthorizationContext> =
    AuthorizationManager { authentication, context ->
        val token = authentication.get() as? JwtAuthenticationToken
        // RequestAuthorizationContext variables are not populated by every matcher
        // implementation; derive the tenant from the already-matched exact path.
        val pathTenantId =
            RUN_SUPERVISION_PATH
                .matchEntire(
                    context.request.requestURI.removePrefix(context.request.contextPath),
                )?.groupValues
                ?.get(1)
        val tokenTenantId = token?.token?.claims?.get(RUN_SUPERVISION_TENANT_CLAIM) as? String
        AuthorizationDecision(
            trust.issuer != null &&
                token?.isAuthenticated == true &&
                // JWT exposes its issuer as URL; configuration stores URI. Exact text
                // comparison retains the configured issuer boundary across those types.
                token.token.issuer?.toString() == trust.issuer.toString() &&
                token.token.audience?.contains(RUN_SUPERVISION_AUDIENCE) == true &&
                token.token.subject?.let {
                    it.isNotBlank() && it.length <= MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH
                } == true &&
                token.authorities.any { it.authority == RUN_SUPERVISION_ASSIGN_SCOPE } &&
                canonicalTenantIdMatches(pathTenantId, tokenTenantId),
        )
    }

private fun canonicalTenantIdMatches(
    pathTenantId: String?,
    tokenTenantId: String?,
): Boolean {
    if (pathTenantId == null || tokenTenantId == null || pathTenantId != tokenTenantId) {
        return false
    }
    return runCatching { UUID.fromString(pathTenantId).toString() == pathTenantId }.getOrDefault(false)
}

private val RUN_SUPERVISION_PATH =
    Regex("^/internal/v1/tenants/([^/]+)/resolution-runs/([^/]+)/supervisor-assignments$")

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
