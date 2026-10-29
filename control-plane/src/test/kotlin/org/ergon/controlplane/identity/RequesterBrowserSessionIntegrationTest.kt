package org.ergon.controlplane.identity

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.identity.application.HumanAuthorityService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.annotation.DirtiesContext
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
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.security.browser-session.audience=requester",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(BrowserSessionTestClientConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequesterBrowserSessionIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val actors: HumanAuthorityService,
    @Autowired private val registrations: ClientRegistrationRepository,
    @Autowired private val mapper: ObjectMapper,
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `requester login binds PKCE to its fixed client and emits an isolated secure cookie`() {
        val response =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().use { client ->
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:$port/bff/login?returnTo=/tenants/${UUID.randomUUID()}"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            }
        assertThat(response.statusCode()).isEqualTo(302)
        val authorizationPath = "/oauth2/authorization/ergon-requester"
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo(authorizationPath)
        val cookie = response.headers().firstValue("Set-Cookie").orElseThrow()
        assertThat(cookie)
            .startsWith("__Host-Http-Ergon-Requester-Session=")
            .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax")
            .doesNotContain("Domain=")
        mockMvc
            .perform(get("/oauth2/authorization/ergon-requester"))
            .andExpect(status().isFound)
            .andExpect(header().string("Location", containsString("client_id=ergon-requester-test")))
            .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
    }

    @Test
    fun `session resolves only a registered tenant actor without subject or tokens`() {
        val tenantId = UUID.randomUUID()
        val actor = actors.registerActor(tenantId, "workforce-sso", "requester-42")
        val actorId =
            actor.actor.id.value
                .toString()
        mockMvc
            .perform(get(SESSION, tenantId).with(requester()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.actorId").value(actorId))
            .andExpect(jsonPath("$.subject").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.idToken").doesNotExist())
        mockMvc
            .perform(get(SESSION, UUID.randomUUID()).with(requester()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
    }

    @Test
    fun `API probes require the requester client and never redirect to provider login`() {
        mockMvc
            .perform(get(SESSION, UUID.randomUUID()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.signInPath").value("/bff/login"))
            .andExpect(header().doesNotExist("Location"))
        val workbench = BrowserSessionTestClientConfiguration().browserClientRegistrationRepository("workbench")
        val workbenchClient = requireNotNull(workbench.findByRegistrationId("ergon-workbench"))
        mockMvc
            .perform(
                get(
                    SESSION,
                    UUID.randomUUID(),
                ).with(oidcLogin().clientRegistration(workbenchClient)),
            ).andExpect(status().isForbidden)
        for (path in listOf("/oauth2/authorization/ergon-workbench", "/login/oauth2/code/ergon-workbench")) {
            mockMvc.perform(get(path).with(requester())).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `requester identity and CSRF never authorize resolver or internal routes`() {
        val tenantId = UUID.randomUUID()
        val paths =
            listOf(
                "/bff/v1/tenants/$tenantId/human-follow-ups",
                "/bff/v1/tenants/$tenantId/human-follow-ups/${UUID.randomUUID()}/information-requests",
                "/internal/v1/tenants/$tenantId/human-actors",
                "/bff/v1/tenants/$tenantId/evidence-proposals/pending",
                "/bff/v1/tenants/$tenantId/documentary-contradictions/claims/${UUID.randomUUID()}",
                "/bff/v1/tenants/$tenantId/documentary-contradictions/adjudications/${UUID.randomUUID()}",
                "/bff/v1/tenants/$tenantId/evidence-proposals/CLAIM/${UUID.randomUUID()}/review",
                "/bff/v1/tenants/$tenantId/evidence-proposals/PROCEDURE/${UUID.randomUUID()}" +
                    "/reviews/${UUID.randomUUID()}",
            )
        for (path in paths) {
            mockMvc.perform(get(path).with(requester())).andExpect(status().isForbidden)
            mockMvc.perform(post(path).with(requester()).with(csrf())).andExpect(status().isForbidden)
        }
        mockMvc
            .perform(get("/api/v1/tenants/$tenantId/human-actor").with(requester()))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/login").with(requester())).andExpect(status().isForbidden)
        mockMvc.perform(post("/logout").with(requester()).with(csrf())).andExpect(status().isForbidden)
    }

    @Test
    fun `session CSRF is no-store and rejects missing and foreign-session tokens before dispatch`() {
        val session = MockHttpSession()
        val result =
            mockMvc
                .perform(get("/bff/v1/csrf").session(session).with(requester()))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn()
        val token = mapper.readTree(result.response.contentAsString)
        val headerName = token["headerName"].asString()
        val value = token["token"].asString()
        mockMvc
            .perform(post("/bff/probe").session(session).with(requester()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        mockMvc
            .perform(post("/bff/probe").session(MockHttpSession()).with(requester()).header(headerName, value))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        // A real matching token passes CSRF but cannot bypass the route allowlist.
        val denied =
            mockMvc
                .perform(post("/bff/probe").session(session).with(requester()).header(headerName, value))
                .andExpect(status().isForbidden)
                .andReturn()
        assertThat(denied.response.contentAsString).doesNotContain("invalid-browser-csrf-token")
        mockMvc
            .perform(post("/bff/probe"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:browser-authentication-required"))
    }

    @Test
    fun `login rejects external and noncanonical return paths`() {
        for (returnTo in listOf(
            "https://attacker.example",
            "//attacker.example",
            "/tenants/1-1-1-1-1",
            "/tenants/${UUID.randomUUID()}?next=x",
        )) {
            mockMvc
                .perform(get("/bff/login").param("returnTo", returnTo))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-return-path"))
        }
    }

    private fun requester() =
        oidcLogin()
            .clientRegistration(requireNotNull(registrations.findByRegistrationId("ergon-requester")))
            .idToken { it.issuer("https://identity.example.test").subject("requester-42") }

    companion object {
        private const val SESSION = "/bff/v1/tenants/{tenantId}/session"

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
