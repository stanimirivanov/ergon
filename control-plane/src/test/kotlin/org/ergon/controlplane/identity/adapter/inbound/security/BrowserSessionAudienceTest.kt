package org.ergon.controlplane.identity.adapter.inbound.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import java.net.URI

class BrowserSessionAudienceTest {
    private val trust = HumanJwtTrust(URI("https://identity.example.test"), "workforce-sso")
    private val registrations = BrowserSessionTestClientConfiguration().browserClientRegistrationRepository("requester")
    private val configuration = BrowserSessionDeploymentConfiguration()

    @Test
    fun `audience is exact startup configuration and rejects unknown values`() {
        assertThat(configuration.browserSessionAudience("workbench", false)).isEqualTo(BrowserSessionAudience.WORKBENCH)
        assertThat(configuration.browserSessionAudience("requester", true)).isEqualTo(BrowserSessionAudience.REQUESTER)
        assertThatIllegalStateException().isThrownBy { configuration.browserSessionAudience("REQUESTER", true) }
    }

    @Test
    fun `requester without sessions and unknown disabled audience fail startup instead of restoring legacy access`() {
        for (audience in listOf("requester", "unknown")) {
            ApplicationContextRunner()
                .withUserConfiguration(BrowserSessionDeploymentConfiguration::class.java)
                .withPropertyValues("ergon.security.browser-session.audience=$audience")
                .withPropertyValues("ergon.security.browser-session.enabled=false")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalStateException::class.java)
                }
        }
    }

    @Test
    fun `requester cannot start with only the workbench registration or a foreign issuer`() {
        assertThatIllegalArgumentException().isThrownBy {
            requiredBrowserRegistration(
                BrowserSessionTestClientConfiguration().browserClientRegistrationRepository("workbench"),
                trust,
                BrowserSessionAudience.REQUESTER,
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            requiredBrowserRegistration(
                registrations,
                HumanJwtTrust(URI("https://foreign.example"), "other"),
                BrowserSessionAudience.REQUESTER,
            )
        }
    }

    @Test
    fun `authorization resolution cannot select another available registration`() {
        val resolver =
            AudienceAuthorizationRequestResolver(
                DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization"),
                "ergon-workbench",
            )
        assertThat(resolver.resolve(MockHttpServletRequest("GET", "/oauth2/authorization/ergon-requester"))).isNull()
        assertThat(resolver.resolve(MockHttpServletRequest(), "ergon-requester")).isNull()
    }

    @Test
    fun `requester registration rejects missing openid and blank confidential secret`() {
        val valid = requireNotNull(registrations.findByRegistrationId("ergon-requester"))
        for (invalid in listOf(
            ClientRegistration.withClientRegistration(valid).scope("profile").build(),
            ClientRegistration.withClientRegistration(valid).clientSecret("").build(),
        )) {
            val repository = ClientRegistrationRepository { invalid }
            assertThatIllegalArgumentException().isThrownBy {
                requiredBrowserRegistration(repository, trust, BrowserSessionAudience.REQUESTER)
            }
        }
    }
}
