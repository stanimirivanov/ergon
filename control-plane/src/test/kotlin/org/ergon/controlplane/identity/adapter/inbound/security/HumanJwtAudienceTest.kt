package org.ergon.controlplane.identity.adapter.inbound.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date
import java.util.UUID

private const val HUMAN_PATH = "/api/v1/tenants/{tenantId}/human-actor"
private const val HUMAN_AUDIENCE = "ergon-human-api"
private const val MACHINE_AUDIENCE = "ergon-run-supervision"

@RestController
@ConditionalOnProperty(prefix = "ergon.test", name = ["human-audience-probe"], havingValue = "true")
internal class HumanAudienceProbeController {
    @GetMapping(HUMAN_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun currentActor() = Unit
}

@WebMvcTest(
    controllers = [HumanAudienceProbeController::class, RunSupervisorAssignmentProbeController::class],
    properties = [
        "ergon.security.human-jwt.identity-provider=workforce-sso",
        "ergon.security.human-jwt.audience=$HUMAN_AUDIENCE",
        "ergon.test.human-audience-probe=true",
        "ergon.test.run-supervisor-probe=true",
    ],
)
@Import(HumanJwtSecurityConfiguration::class, RunSupervisorAssignmentWebSecurityTestConfiguration::class)
class HumanJwtAudienceTest(
    @Autowired private val mvc: MockMvc,
) {
    private val tenant = UUID.randomUUID()

    @Test
    fun `human endpoint rejects signed tokens missing its audience`() {
        for (audience in listOf(null, "unrelated-service", MACHINE_AUDIENCE)) {
            mvc
                .perform(get(HUMAN_PATH, tenant).header(HttpHeaders.AUTHORIZATION, bearer(audience)))
                .andExpect(status().isForbidden)
        }
    }

    @Test
    fun `signed human token reaches its endpoint and machine token reaches assignment`() {
        mvc
            .perform(get(HUMAN_PATH, tenant).header(HttpHeaders.AUTHORIZATION, bearer(HUMAN_AUDIENCE)))
            .andExpect(status().isNoContent)
        mvc
            .perform(
                post(
                    "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/supervisor-assignments",
                    tenant,
                    UUID.randomUUID(),
                ).header(HttpHeaders.AUTHORIZATION, bearer(MACHINE_AUDIENCE)),
            ).andExpect(status().isNoContent)
    }

    private fun bearer(audience: String?): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .subject("employee-42")
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .claim("scope", "ergon.run-supervision.assign")
                .claim("ergon_tenant_id", tenant.toString())
        audience?.let { claims.audience(it) }
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.keyID)
                .build()
        val jwt = SignedJWT(header, claims.build())
        jwt.sign(RSASSASigner(key))
        return "Bearer ${jwt.serialize()}"
    }

    companion object {
        private val key = RSAKeyGenerator(2048).keyID("test").generate()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val issuer = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/.well-known/openid-configuration") { exchange ->
                val metadata = """{"issuer":"$issuer","jwks_uri":"$issuer/jwks"}"""
                exchange.responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json")
                val bytes = metadata.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/jwks") { exchange ->
                exchange.responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json")
                val bytes = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun jwtIssuer(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { issuer }
        }

        @AfterAll
        @JvmStatic
        fun stopIssuer() {
            server.stop(0)
        }
    }
}

@WebMvcTest(
    controllers = [HumanAudienceProbeController::class],
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
        "ergon.security.human-jwt.audience=",
        "ergon.test.human-audience-probe=true",
    ],
)
@Import(HumanJwtSecurityConfiguration::class, RunSupervisorAssignmentWebSecurityTestConfiguration::class)
class HumanJwtWithoutAudienceTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `human endpoint fails closed without a configured audience`() {
        mvc
            .perform(get(HUMAN_PATH, UUID.randomUUID()).with(jwt().jwt { it.audience(listOf(HUMAN_AUDIENCE)) }))
            .andExpect(status().isForbidden)
    }
}
