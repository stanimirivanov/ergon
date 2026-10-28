package org.ergon.controlplane.identity.adapter.inbound.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

/**
 * Deployment-selected browser boundary, never a role or a client-supplied scope.
 *
 * Each audience runs on a separate origin/process with a distinct provider client.
 * Selecting REQUESTER exposes only the explicit requester allowlist, not resolver APIs.
 */
enum class BrowserSessionAudience(
    val registrationId: String,
    val sessionCookieName: String,
) {
    WORKBENCH(BROWSER_REGISTRATION_ID, "__Host-Http-Ergon-Session"),
    REQUESTER("ergon-requester", "__Host-Http-Ergon-Requester-Session"),
}

/** Validates topology even when browser sessions are disabled, preventing legacy API fallback. */
@Configuration(proxyBeanMethods = false)
class BrowserSessionDeploymentConfiguration {
    /** Rejects unknown audiences and requester deployments without their protective session boundary. */
    @Bean
    @Lazy(false)
    fun browserSessionAudience(
        @Value("\${ergon.security.browser-session.audience:workbench}") audience: String,
        @Value("\${ergon.security.browser-session.enabled:false}") enabled: Boolean,
    ): BrowserSessionAudience {
        val selected =
            when (audience) {
                "workbench" -> BrowserSessionAudience.WORKBENCH
                "requester" -> BrowserSessionAudience.REQUESTER
                else -> error("browser session audience must be workbench or requester")
            }
        check(selected != BrowserSessionAudience.REQUESTER || enabled) {
            "requester deployments require browser sessions; stop the deployment to disable access"
        }
        return selected
    }
}

/** Omits the legacy API chain when requester security deliberately covers every request. */
internal class NonRequesterDeployment : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.getProperty("ergon.security.browser-session.audience", "workbench") != "requester"
}

/**
 * Limits initiation before Spring's resolver can look up another configured client.
 * Callback matching is separately restricted in the filter chain.
 */
internal class AudienceAuthorizationRequestResolver(
    private val delegate: DefaultOAuth2AuthorizationRequestResolver,
    private val registrationId: String,
) : OAuth2AuthorizationRequestResolver {
    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (request.method != "GET" || path != "/oauth2/authorization/$registrationId") return null
        return delegate.resolve(request)
    }

    override fun resolve(
        request: HttpServletRequest,
        clientRegistrationId: String,
    ): OAuth2AuthorizationRequest? {
        if (clientRegistrationId != registrationId) return null
        return delegate.resolve(request, clientRegistrationId)
    }
}
