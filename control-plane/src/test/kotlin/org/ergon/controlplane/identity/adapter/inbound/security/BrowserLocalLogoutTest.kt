package org.ergon.controlplane.identity.adapter.inbound.security

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.adapter.inbound.http.BrowserCsrfTokenResponse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant

/** Exercises the real browser filter chain without starting database or provider infrastructure. */
class BrowserLocalLogoutTest {
    @ParameterizedTest
    @EnumSource(BrowserSessionAudience::class)
    fun `cookie deletion respects configured names for each audience`(audience: BrowserSessionAudience) =
        withBrowser(audience, "__Host-Http-Custom-Session") { mvc, mapper ->
            val session = authenticatedSession(identity(audience.registrationId))
            val token = token(mvc, mapper, session)
            val expectedName =
                when (audience) {
                    BrowserSessionAudience.REQUESTER -> audience.sessionCookieName
                    BrowserSessionAudience.WORKBENCH -> "__Host-Http-Custom-Session"
                }
            mvc
                .perform(post("/bff/logout").session(session).header(token.first, token.second))
                .andExpect(status().isNoContent)
                .andDo { result ->
                    assertThat(result.response.getHeaders("Set-Cookie")).hasSize(1)
                    assertThat(result.response.getHeader("Set-Cookie")).startsWith("$expectedName=")
                }
        }

    @ParameterizedTest
    @EnumSource(BrowserSessionAudience::class)
    fun `logout retires local credentials but preserves another session`(audience: BrowserSessionAudience) =
        withBrowser(audience) { mvc, mapper ->
            val authentication = identity(audience.registrationId)
            val session = authenticatedSession(authentication)
            val other = authenticatedSession(authentication)
            val clients = HttpSessionOAuth2AuthorizedClientRepository()
            val registration =
                BrowserSessionTestClientConfiguration()
                    .browserClientRegistrationRepository(audience.name.lowercase())
                    .findByRegistrationId(audience.registrationId)!!
            val at = Instant.parse("2026-10-28T10:00:00Z")
            val client =
                OAuth2AuthorizedClient(
                    registration,
                    authentication.name,
                    OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "synthetic-token", at, at.plusSeconds(300)),
                )
            for (owned in listOf(session, other)) {
                clients.saveAuthorizedClient(client, authentication, request(owned), MockHttpServletResponse())
            }
            session.setAttribute(BROWSER_RETURN_TO_SESSION_ATTRIBUTE, "/tenants/private")
            val token = token(mvc, mapper, session)
            mvc
                .perform(post("/bff/logout").session(session).header(token.first, token.second))
                .andExpect(status().isNoContent)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist("Clear-Site-Data"))
                .andDo { result ->
                    val cookie = result.response.getHeader("Set-Cookie")!!
                    assertThat(cookie).contains("Max-Age=0", "Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                    assertThat(cookie).startsWith(audience.sessionCookieName + "=").doesNotContain("Domain=")
                    assertThat(result.response.contentAsByteArray).isEmpty()
                }
            assertThat(session.isInvalid).isTrue()
            assertThat(other.isInvalid).isFalse()
            val retained =
                clients.loadAuthorizedClient<OAuth2AuthorizedClient>(
                    audience.registrationId,
                    authentication,
                    request(other),
                )
            assertThat(retained).isSameAs(client)
            mvc.perform(get(SESSION_PROBE).session(other)).andExpect(status().isOk)
            mvc.perform(get(SESSION_PROBE)).andExpect(status().isUnauthorized)
            mvc.perform(post("/bff/logout").header(token.first, token.second)).andExpect(status().isUnauthorized)
            mvc
                .perform(post("/bff/logout").session(other).header(token.first, token.second))
                .andExpect(status().isForbidden)
            assertThat(other.isInvalid).isFalse()
        }

    @ParameterizedTest
    @EnumSource(BrowserSessionAudience::class)
    fun `CSRF and exact POST route are mandatory before session destruction`(audience: BrowserSessionAudience) =
        withBrowser(audience) { mvc, mapper ->
            val session = authenticatedSession(identity(audience.registrationId))
            val foreign = authenticatedSession(identity(audience.registrationId))
            val foreignToken = token(mvc, mapper, foreign)
            mvc
                .perform(post("/bff/logout").session(session))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
            mvc
                .perform(post("/bff/logout").session(session).header("X-CSRF-TOKEN", "tampered"))
                .andExpect(status().isForbidden)
            mvc
                .perform(post("/bff/logout").session(session).header(foreignToken.first, foreignToken.second))
                .andExpect(status().isForbidden)
            for (path in listOf("/bff/logout", "/logout")) {
                mvc
                    .perform(get(path).session(session))
                    .andExpect(status().isForbidden)
                    .andExpect(header().doesNotExist("Location"))
            }
            mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().isForbidden)
            assertThat(session.isInvalid).isFalse()
            mvc.perform(get(SESSION_PROBE).session(session)).andExpect(status().isOk)
        }

    @ParameterizedTest
    @EnumSource(BrowserSessionAudience::class)
    fun `anonymous and other identities cannot activate logout filter`(audience: BrowserSessionAudience) =
        withBrowser(audience) { mvc, _ ->
            mvc.perform(post("/bff/logout").with(csrf())).andExpect(status().isUnauthorized)
            val otherClient = authenticatedSession(identity("other-client"))
            mvc.perform(post("/bff/logout").session(otherClient).with(csrf())).andExpect(status().isForbidden)
            assertThat(otherClient.isInvalid).isFalse()
            for (authentication in listOf(
                UsernamePasswordAuthenticationToken.authenticated("human", "unused", emptyList()),
                OAuth2AuthenticationToken(
                    DefaultOAuth2User(emptyList(), mapOf("sub" to "human"), "sub"),
                    emptyList(),
                    audience.registrationId,
                ),
                JwtAuthenticationToken(
                    Jwt
                        .withTokenValue("synthetic-bearer")
                        .header("alg", "none")
                        .subject("human")
                        .build(),
                ),
            )) {
                val nonOidc = MockHttpSession()
                nonOidc.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    SecurityContextImpl(authentication),
                )
                mvc.perform(post("/bff/logout").session(nonOidc).with(csrf())).andExpect(status().isForbidden)
                assertThat(nonOidc.isInvalid).isFalse()
            }
        }

    private fun token(
        mvc: MockMvc,
        mapper: ObjectMapper,
        session: MockHttpSession,
    ): Pair<String, String> {
        val result = mvc.perform(get("/bff/v1/csrf").session(session)).andExpect(status().isOk).andReturn()
        val body = mapper.readTree(result.response.contentAsString)
        return body["headerName"].asString() to body["token"].asString()
    }

    private fun identity(registrationId: String): OAuth2AuthenticationToken {
        val idToken = OidcIdToken.withTokenValue("synthetic-id-token").subject("same-human").build()
        val principal = DefaultOidcUser(emptyList(), idToken)
        return OAuth2AuthenticationToken(principal, principal.authorities, registrationId)
    }

    private fun authenticatedSession(authentication: OAuth2AuthenticationToken): MockHttpSession =
        MockHttpSession().apply {
            setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextImpl(authentication),
            )
        }

    private fun request(owned: MockHttpSession): MockHttpServletRequest {
        val request = MockHttpServletRequest()
        request.setSession(owned)
        return request
    }

    private fun withBrowser(
        audience: BrowserSessionAudience,
        workbenchCookieName: String = BrowserSessionAudience.WORKBENCH.sessionCookieName,
        verify: (MockMvc, ObjectMapper) -> Unit,
    ) {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "logout-test",
                    mapOf(
                        "ergon.security.browser-session.enabled" to "true",
                        "ergon.test.local-logout-probe" to "true",
                        "ergon.security.browser-session.audience" to audience.name.lowercase(),
                        "server.servlet.session.cookie.name" to workbenchCookieName,
                    ),
                ),
            )
            context.register(BrowserLogoutTestConfiguration::class.java)
            context.refresh()
            val mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
            verify(mvc, context.getBean(ObjectMapper::class.java))
        }
    }

    private companion object {
        const val SESSION_PROBE = "/bff/v1/tenants/00000000-0000-4000-8000-000000000001/session"
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableWebMvc
@Import(BrowserSessionSecurityConfiguration::class, BrowserLogoutProbe::class)
internal class BrowserLogoutTestConfiguration {
    @Bean
    fun audience(
        @Value("\${ergon.security.browser-session.audience}") value: String,
    ) = BrowserSessionAudience.valueOf(value.uppercase())

    @Bean
    fun trust() = HumanJwtTrust(URI("https://identity.example.test"), "workforce-sso")

    @Bean
    fun registrations(audience: BrowserSessionAudience): ClientRegistrationRepository =
        BrowserSessionTestClientConfiguration().browserClientRegistrationRepository(audience.name.lowercase())

    @Bean
    fun mapper(): ObjectMapper = JsonMapper.builder().build()
}

@RestController
@ConditionalOnProperty(prefix = "ergon.test", name = ["local-logout-probe"], havingValue = "true")
internal class BrowserLogoutProbe {
    @GetMapping("/bff/v1/csrf")
    fun csrf(token: CsrfToken) = BrowserCsrfTokenResponse(token.headerName, token.token)

    @GetMapping("/bff/v1/tenants/{tenantId}/session")
    fun probe() = mapOf("checked" to true)
}
