package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.identity.BrowserSessionTestClientConfiguration
import org.ergon.controlplane.identity.application.HumanAuthorityService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest(
    properties = [
        "ergon.security.browser-session.enabled=true",
        "ergon.security.browser-session.audience=requester",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Import(BrowserSessionTestClientConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequesterCaseIntakeHttpIntegrationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val actors: HumanAuthorityService,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val registrations: ClientRegistrationRepository,
) {
    private val mapper = JsonMapper.builder().build()
    private val tenant = UUID.randomUUID()
    private val case = UUID.randomUUID()
    private val subject = UUID.randomUUID().toString()
    private val path = "/bff/v1/tenants/$tenant/requester-cases"

    @Test
    fun `verified creator gets only original intake and independently fresh progress`() {
        val owner = register(subject)
        val other = register("other-$subject")
        val body = body() + ("requesterActorId" to other.toString())
        val first = send(body, 201)
        val receipt = mapper.readTree(first)
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder(
            "caseId",
            "goal",
            "initialObservation",
            "openedAt",
            "recordedAt",
        )
        assertThat(receipt["goal"].asString()).isEqualTo("Restore access")
        assertThat(receipt["initialObservation"].asString()).isEqualTo("<script>untrusted</script> 😀")
        assertThat(send(body, 200)).isEqualTo(first)
        mvc
            .perform(get("$path/$case/progress").with(login()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("OPEN"))
        mvc.perform(get("$path/$case/progress").with(login("other-$subject"))).andExpect(status().isNotFound)
        val actual =
            jdbc
                .sql("SELECT requester_actor_id FROM case_requesters WHERE tenant_id = :tenant AND case_id = :case")
                .param("tenant", tenant)
                .param("case", case)
                .query(UUID::class.java)
                .single()
        assertThat(actual).isEqualTo(owner)
        // Only a later lifecycle projection is simulated, not the proof needed to establish it.
        jdbc
            .sql("UPDATE cases SET status = 'VERIFIED_RESOLVED' WHERE tenant_id = :tenant AND case_id = :case")
            .param("tenant", tenant)
            .param("case", case)
            .update()
        assertThat(send(body, 200)).isEqualTo(first)
        assertThat(count()).isEqualTo(1)
    }

    @Test
    fun `conflicts never disclose another creator or opening content`() {
        register(subject)
        register("other-$subject")
        send(body(), 201)
        for ((input, caller) in listOf(body("Changed") to subject, body() to "other-$subject")) {
            mvc
                .perform(
                    post(path)
                        .with(login(caller))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input)),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:requester-case-intake-conflict"))
                .andExpect(jsonPath("$.detail").value("Requester intake intent conflicts"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
        }
        assertThat(count()).isEqualTo(1)
    }

    @Test
    fun `session audience tenant and method failures cannot reach intake`() {
        register(subject)
        val request = mapper.writeValueAsString(body())
        mvc
            .perform(
                post(path).contentType(MediaType.APPLICATION_JSON).content(request),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(post(path).with(login()).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden)
        val workbench = BrowserSessionTestClientConfiguration().browserClientRegistrationRepository("workbench")
        val otherRegistration = requireNotNull(workbench.findByRegistrationId("ergon-workbench"))
        val otherClient = oidcLogin().clientRegistration(otherRegistration)
        mvc
            .perform(
                post(path)
                    .with(csrf())
                    .with(otherClient)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path)
                    .with(login("unregistered"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
        mvc
            .perform(
                post("/bff/v1/tenants/${UUID.randomUUID()}/requester-cases")
                    .with(login())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-registered"))
        mvc
            .perform(
                post(path)
                    .with(login(issuer = "https://untrusted.example.test"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request),
            ).andExpect(status().isForbidden)
        mvc.perform(get(path).with(login())).andExpect(status().isForbidden)
        mvc.perform(put(path).with(login()).with(csrf())).andExpect(status().isForbidden)
        assertThat(count()).isZero()
    }

    @Test
    fun `CSRF is bound to its requester session rather than copied into a new session`() {
        register(subject)
        val session = MockHttpSession()
        val response =
            mvc
                .perform(get("/bff/v1/csrf").session(session).with(login()))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val token = mapper.readTree(response.contentAsString)
        val request = mapper.writeValueAsString(body())
        mvc
            .perform(
                post(path)
                    .session(MockHttpSession())
                    .with(login())
                    .header(token["headerName"].asString(), token["token"].asString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-browser-csrf-token"))
        assertThat(count()).isZero()
        mvc
            .perform(
                post(path)
                    .session(session)
                    .with(login())
                    .header(token["headerName"].asString(), token["token"].asString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request),
            ).andExpect(status().isCreated)
        assertThat(count()).isEqualTo(1)
    }

    @Test
    fun `malformed and invalid inputs return only the safe input problem without writes`() {
        register(subject)
        val invalid =
            listOf(
                "{",
                "{}",
                mapper.writeValueAsString(body() + ("caseId" to "bad-uuid")),
                mapper.writeValueAsString(body(" ")),
                mapper.writeValueAsString(body("x".repeat(501))),
                mapper.writeValueAsString(body() + ("initialObservation" to "bad\u0000text")),
            )
        for (request in invalid) {
            mvc
                .perform(
                    post(path)
                        .with(login())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-requester-case-intake"))
                .andExpect(jsonPath("$.detail").value("Invalid requester case intake"))
        }
        assertThat(count()).isZero()
    }

    private fun body(goal: String = " Restore access "): Map<String, String> =
        mapOf("caseId" to case.toString(), "goal" to goal, "initialObservation" to " <script>untrusted</script> 😀 ")

    private fun send(
        body: Map<String, String>,
        expected: Int,
    ): String =
        mvc
            .perform(
                post(path)
                    .with(login())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)),
            ).andExpect(status().`is`(expected))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andReturn()
            .response.contentAsString

    private fun register(subject: String): UUID =
        actors
            .registerActor(tenant, "workforce-sso", subject)
            .actor.id.value

    private fun login(
        subject: String = this.subject,
        issuer: String = "https://identity.example.test",
    ) = oidcLogin()
        .clientRegistration(requireNotNull(registrations.findByRegistrationId("ergon-requester")))
        .idToken { it.subject(subject).issuer(issuer) }

    private fun count(): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM case_events WHERE tenant_id = :tenant AND case_id = :case")
            .param("tenant", tenant)
            .param("case", case)
            .query(Long::class.java)
            .single()

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        @DynamicPropertySource @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
