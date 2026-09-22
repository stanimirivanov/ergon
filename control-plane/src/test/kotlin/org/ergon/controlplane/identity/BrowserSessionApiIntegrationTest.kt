package org.ergon.controlplane.identity

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.identity.adapter.inbound.security.BROWSER_REGISTRATION_ID
import org.ergon.controlplane.identity.adapter.inbound.security.BROWSER_RETURN_TO_SESSION_ATTRIBUTE
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class)
class BrowserSessionApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `resolves a verified OIDC session without disclosing tokens or subject`() {
        val tenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)

        mockMvc
            .perform(
                get(SESSION_PATH, tenantId).with(
                    oidcLogin().idToken {
                        it.issuer(TRUSTED_ISSUER)
                        it.subject("employee-42")
                    },
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.actorId").value(actorId.toString()))
            .andExpect(jsonPath("$.identityProvider").value("workforce-sso"))
            .andExpect(jsonPath("$.registeredAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.idToken").doesNotExist())
    }

    @Test
    fun `returns a sign-in contract instead of redirecting an API probe`() {
        mockMvc
            .perform(get(SESSION_PATH, UUID.randomUUID()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
            .andExpect(jsonPath("$.signInPath").value("/bff/login"))
            .andExpect(header().doesNotExist("Location"))
    }

    @Test
    fun `does not disclose a tenant-foreign actor binding`() {
        val registeredTenantId = UUID.randomUUID()
        registerActor(registeredTenantId)

        mockMvc
            .perform(
                get(SESSION_PATH, UUID.randomUUID()).with(
                    oidcLogin().idToken {
                        it.issuer(TRUSTED_ISSUER)
                        it.subject("employee-42")
                    },
                ),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
    }

    @Test
    fun `starts PKCE login and keeps only a canonical tenant return path`() {
        val tenantId = UUID.randomUUID()
        val login =
            mockMvc
                .perform(get("/bff/login").param("returnTo", "/tenants/$tenantId"))
                .andExpect(status().isFound)
                .andExpect(header().string("Location", "/oauth2/authorization/$BROWSER_REGISTRATION_ID"))
                .andReturn()

        val session = requireNotNull(login.request.getSession(false))
        assertThat(session).isInstanceOf(MockHttpSession::class.java)
        val mockSession = session as MockHttpSession
        assertThat(session.getAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE)).isEqualTo("/tenants/$tenantId")

        mockMvc
            .perform(get("/oauth2/authorization/$BROWSER_REGISTRATION_ID").session(mockSession))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", containsString("response_type=code")))
            .andExpect(header().string("Location", containsString("code_challenge=")))
            .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
    }

    @Test
    fun `rejects an external post-login return location`() {
        mockMvc
            .perform(get("/bff/login").param("returnTo", "https://attacker.example/collect"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-return-path"))
    }

    private fun registerActor(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(
                    post(ACTORS_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTOR_REQUEST),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val SESSION_PATH = "/bff/v1/tenants/{tenantId}/session"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val ACTOR_REQUEST =
            """{"identityProvider":"workforce-sso","subject":"employee-42"}"""

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class BrowserSessionTestClientConfiguration {
    @Bean
    fun browserClientRegistrationRepository(): ClientRegistrationRepository =
        InMemoryClientRegistrationRepository(
            ClientRegistration
                .withRegistrationId(BROWSER_REGISTRATION_ID)
                .clientId("ergon-workbench-test")
                .clientSecret("test-only-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("$TRUSTED_ISSUER/oauth2/authorize")
                .tokenUri("$TRUSTED_ISSUER/oauth2/token")
                .jwkSetUri("$TRUSTED_ISSUER/oauth2/jwks")
                .issuerUri(TRUSTED_ISSUER)
                .userInfoUri("$TRUSTED_ISSUER/userinfo")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Ergon Workbench Test")
                .build(),
        )

    private companion object {
        const val TRUSTED_ISSUER = "https://identity.example.test"
    }
}
