package org.ergon.controlplane.identity.adapter.inbound.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val ASSIGNMENT_PATH = "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/supervisor-assignments"
private const val TRUSTED_ISSUER = "https://identity.example.test"
private const val ASSIGNMENT_AUDIENCE = "ergon-run-supervision"
private const val ASSIGNMENT_SCOPE = "ergon.run-supervision.assign"

/** Proves the security filter acts before a controller handles an assignment. */
@RestController
@ConditionalOnProperty(prefix = "ergon.test", name = ["run-supervisor-probe"], havingValue = "true")
internal class RunSupervisorAssignmentProbeController {
    @PostMapping(ASSIGNMENT_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun assign() {
        // Reaching this method proves the assignment token passed the filter.
    }
}

/** Supplies HttpSecurity to the isolated web-slice context. */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
internal class RunSupervisorAssignmentWebSecurityTestConfiguration

@WebMvcTest(
    controllers = [RunSupervisorAssignmentProbeController::class],
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=$TRUSTED_ISSUER",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
        "ergon.test.run-supervisor-probe=true",
    ],
)
@Import(HumanJwtSecurityConfiguration::class, RunSupervisorAssignmentWebSecurityTestConfiguration::class)
class RunSupervisorAssignmentSecurityTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private val tenantId = UUID.randomUUID()
    private val runId = UUID.randomUUID()

    @Test
    fun `rejects unauthenticated assignment before dispatch`() {
        mockMvc.perform(assignmentRequest()).andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects human tokens without the machine scope and audience`() {
        mockMvc
            .perform(assignmentRequest().with(machineJwt(scope = "read", audience = listOf("ergon-workbench"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `requires both the exact assignment scope and audience`() {
        mockMvc
            .perform(assignmentRequest().with(machineJwt(scope = "read")))
            .andExpect(status().isForbidden)
        mockMvc
            .perform(assignmentRequest().with(machineJwt(audience = listOf("ergon-workbench"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `rejects a token from a different issuer even with scope and audience`() {
        mockMvc
            .perform(assignmentRequest().with(machineJwt(issuer = "https://other.example.test")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `rejects a machine token bound to a different tenant`() {
        mockMvc
            .perform(assignmentRequest().with(machineJwt(tenantIdClaim = UUID.randomUUID().toString())))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `rejects a machine token without a tenant claim`() {
        mockMvc
            .perform(assignmentRequest().with(machineJwt(tenantIdClaim = null)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `rejects a machine token without a usable subject`() {
        mockMvc
            .perform(
                assignmentRequest().with(
                    jwt()
                        .jwt {
                            it.issuer(TRUSTED_ISSUER)
                            it.subject("")
                            it.claim("scope", ASSIGNMENT_SCOPE)
                            it.claim("aud", listOf(ASSIGNMENT_AUDIENCE))
                            it.claim("ergon_tenant_id", tenantId.toString())
                        }.authorities(JwtGrantedAuthoritiesConverter()),
                ),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `rejects noncanonical tenant coordinates`() {
        mockMvc
            .perform(assignmentRequest(pathTenantId = tenantId.toString().uppercase()).with(machineJwt()))
            .andExpect(status().isForbidden)
        mockMvc
            .perform(assignmentRequest().with(machineJwt(tenantIdClaim = tenantId.toString().uppercase())))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `assignment path variant cannot fall through to permissive internal routes`() {
        mockMvc
            .perform(
                post("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/supervisor-assignments/", tenantId, runId)
                    .with(machineJwt()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `accepts only a trusted assignment token for the exact tenant`() {
        mockMvc.perform(assignmentRequest().with(machineJwt())).andExpect(status().isNoContent)
    }

    private fun machineJwt(
        issuer: String = TRUSTED_ISSUER,
        scope: String = ASSIGNMENT_SCOPE,
        audience: List<String> = listOf(ASSIGNMENT_AUDIENCE),
        tenantIdClaim: String? = tenantId.toString(),
    ) = jwt()
        .jwt {
            it.issuer(issuer)
            it.claim("scope", scope)
            it.claim("aud", audience)
            tenantIdClaim?.let { claim -> it.claim("ergon_tenant_id", claim) }
        }.authorities(JwtGrantedAuthoritiesConverter())

    private fun assignmentRequest(pathTenantId: String = tenantId.toString()) =
        post(
            ASSIGNMENT_PATH,
            pathTenantId,
            runId,
        )
}

@WebMvcTest(
    controllers = [RunSupervisorAssignmentProbeController::class],
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
        "ergon.test.run-supervisor-probe=true",
    ],
)
@Import(HumanJwtSecurityConfiguration::class, RunSupervisorAssignmentWebSecurityTestConfiguration::class)
class RunSupervisorAssignmentWithoutIssuerSecurityTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `fails closed when the human JWT issuer is unconfigured`() {
        val tenantId = UUID.randomUUID()
        mockMvc
            .perform(
                post(ASSIGNMENT_PATH, tenantId, UUID.randomUUID())
                    .with(
                        jwt()
                            .jwt {
                                it.issuer(TRUSTED_ISSUER)
                                it.claim("scope", ASSIGNMENT_SCOPE)
                                it.claim("aud", listOf(ASSIGNMENT_AUDIENCE))
                                it.claim("ergon_tenant_id", tenantId.toString())
                            }.authorities(JwtGrantedAuthoritiesConverter()),
                    ),
            ).andExpect(status().isForbidden)
    }
}
