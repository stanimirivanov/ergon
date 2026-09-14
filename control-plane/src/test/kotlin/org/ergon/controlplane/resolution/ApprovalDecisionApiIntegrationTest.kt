package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example.test",
        "ergon.security.human-jwt.identity-provider=workforce-sso",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApprovalDecisionApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `authenticated actor records one immutable decision with current requester evidence`() {
        val tenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)
        val caseId = caseIdForRun(tenantId, runId)
        val requestId = createApprovalRequest(tenantId, runId)
        val actorId = registerActor(tenantId, "employee-42")
        val evidenceId = attestRequesterAuthority(tenantId, actorId, caseId)

        mockMvc
            .perform(decide(tenantId, requestId, "employee-42", "MAYBE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-approval-decision"))

        mockMvc
            .perform(decide(tenantId, requestId, "employee-42", "APPROVED"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.approvalRequestId").value(requestId.toString()))
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.actorId").value(actorId.toString()))
            .andExpect(jsonPath("$.authorityEvidenceId").value(evidenceId.toString()))
            .andExpect(jsonPath("$.authority").value("REQUESTER"))
            .andExpect(jsonPath("$.outcome").value("APPROVED"))
            .andExpect(jsonPath("$.decidedAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())
        val decisionId = decisionIdForRequest(tenantId, requestId)
        val grantId = createAndAssertAuthorizationGrant(tenantId, decisionId, requestId, runId, caseId)
        consumeAndAssertAuthorizationGrant(tenantId, grantId, runId, caseId)

        mockMvc
            .perform(decide(tenantId, requestId, "employee-42", "REJECTED"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-decision-already-exists"))
            .andExpect(jsonPath("$.approvalDecisionId").value(decisionId.toString()))

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_approval_decisions
                    SET outcome = outcome
                    WHERE tenant_id = :tenantId AND approval_decision_id = :decisionId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("decisionId", decisionId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `decision requires authentication current request and matching scoped authority`() {
        val tenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)
        val caseId = caseIdForRun(tenantId, runId)
        val requestId = createApprovalRequest(tenantId, runId)
        val actorId = registerActor(tenantId, "employee-99")
        val otherTenantId = UUID.randomUUID()
        registerActor(otherTenantId, "employee-99")

        mockMvc
            .perform(decide(otherTenantId, requestId, "employee-99", "APPROVED"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-request-not-found"))

        mockMvc
            .perform(
                post(APPROVAL_DECISION_PATH, tenantId, requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(APPROVED_DECISION),
            ).andExpect(status().isUnauthorized)

        expectCurrentAuthorityNotFound(tenantId, requestId)

        val unrelatedCaseId = openCase(tenantId)
        attestRequesterAuthority(tenantId, actorId, unrelatedCaseId)
        expectCurrentAuthorityNotFound(tenantId, requestId)

        insertExpiredRequesterAuthority(tenantId, actorId, caseId)
        expectCurrentAuthorityNotFound(tenantId, requestId)

        val expiredRequestId = insertExpiredApprovalRequest(tenantId, runId)
        attestRequesterAuthority(tenantId, actorId, caseId)
        mockMvc
            .perform(decide(tenantId, expiredRequestId, "employee-99", "APPROVED"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-request-expired"))
    }

    @Test
    fun `authorization requires an approved decision in the same tenant`() {
        val tenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)
        val caseId = caseIdForRun(tenantId, runId)
        val requestId = createApprovalRequest(tenantId, runId)
        val actorId = registerActor(tenantId, "employee-authorization")
        attestRequesterAuthority(tenantId, actorId, caseId)

        mockMvc
            .perform(decide(tenantId, requestId, "employee-authorization", "REJECTED"))
            .andExpect(status().isCreated)
        val decisionId = decisionIdForRequest(tenantId, requestId)

        mockMvc
            .perform(post(AUTHORIZATION_GRANTS_PATH, tenantId, decisionId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-decision-not-approved"))

        mockMvc
            .perform(post(AUTHORIZATION_GRANTS_PATH, UUID.randomUUID(), decisionId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-decision-not-found"))
    }

    private fun createAndAssertAuthorizationGrant(
        tenantId: UUID,
        decisionId: UUID,
        requestId: UUID,
        runId: UUID,
        caseId: UUID,
    ): UUID {
        mockMvc
            .perform(post(AUTHORIZATION_GRANTS_PATH, tenantId, decisionId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.approvalDecisionId").value(decisionId.toString()))
            .andExpect(jsonPath("$.approvalRequestId").value(requestId.toString()))
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.policyRevision").value("ergon.dev/policy/access-restoration/v1"))
            .andExpect(jsonPath("$.stepId").value("unlock-account"))
            .andExpect(jsonPath("$.capability").value("identity.account.unlock"))
            .andExpect(jsonPath("$.authorizedAt").exists())
            .andExpect(jsonPath("$.expiresAt").value(requestExpiresAt(tenantId, requestId).toString()))
            .andExpect(jsonPath("$.recordedAt").exists())
        val grantId = authorizationGrantIdForDecision(tenantId, decisionId)

        mockMvc
            .perform(post(AUTHORIZATION_GRANTS_PATH, tenantId, decisionId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:capability-authorization-grant-already-exists"))
            .andExpect(jsonPath("$.authorizationGrantId").value(grantId.toString()))

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE capability_authorization_grants
                    SET capability = capability
                    WHERE tenant_id = :tenantId AND authorization_grant_id = :grantId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("grantId", grantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        return grantId
    }

    private fun consumeAndAssertAuthorizationGrant(
        tenantId: UUID,
        grantId: UUID,
        runId: UUID,
        caseId: UUID,
    ) {
        mockMvc
            .perform(post(AUTHORIZATION_CONSUMPTIONS_PATH, UUID.randomUUID(), grantId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:capability-authorization-grant-not-found"))

        val otherTenantId = UUID.randomUUID()
        insertCapabilityRoute(otherTenantId)
        mockMvc
            .perform(post(AUTHORIZATION_CONSUMPTIONS_PATH, tenantId, grantId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:tenant-capability-unavailable"))
            .andExpect(jsonPath("$.capability").value("identity.account.unlock"))

        insertCapabilityRoute(tenantId)
        assertCapabilityRouteImmutable(tenantId)
        mockMvc
            .perform(post(AUTHORIZATION_CONSUMPTIONS_PATH, tenantId, grantId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.authorizationGrantId").value(grantId.toString()))
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.policyRevision").value("ergon.dev/policy/access-restoration/v1"))
            .andExpect(jsonPath("$.stepId").value("unlock-account"))
            .andExpect(jsonPath("$.capability").value("identity.account.unlock"))
            .andExpect(jsonPath("$.connector").value("identity-stub"))
            .andExpect(jsonPath("$.consumedAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())
        val consumptionId = authorizationConsumptionIdForGrant(tenantId, grantId)

        mockMvc
            .perform(post(AUTHORIZATION_CONSUMPTIONS_PATH, tenantId, grantId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:capability-authorization-already-consumed"))
            .andExpect(jsonPath("$.authorizationConsumptionId").value(consumptionId.toString()))

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE capability_authorization_consumptions
                    SET connector = connector
                    WHERE tenant_id = :tenantId AND authorization_consumption_id = :consumptionId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("consumptionId", consumptionId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun expectCurrentAuthorityNotFound(
        tenantId: UUID,
        requestId: UUID,
    ) {
        mockMvc
            .perform(decide(tenantId, requestId, "employee-99", "APPROVED"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:current-approval-authority-not-found"))
    }

    private fun prepareResolutionRun(tenantId: UUID): UUID {
        val caseId = openCase(tenantId)
        publishContract(tenantId)
        pinContract(tenantId, caseId)
        recordAndBindLockedAccount(tenantId, caseId)
        val response =
            mockMvc
                .perform(post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId).header("If-Match", "\"4\""))
                .andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun openCase(tenantId: UUID): UUID {
        val response =
            mockMvc
                .perform(
                    post(CASE_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_CASE),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(
            requireNotNull(response.response.getHeader("Location"))
                .removeSuffix("/timeline")
                .substringAfterLast('/'),
        )
    }

    private fun publishContract(tenantId: UUID) {
        mockMvc
            .perform(
                post(CONTRACT_REVISIONS_PATH, tenantId)
                    .contentType("application/yaml")
                    .content(VALID_CONTRACT),
            ).andExpect(status().isCreated)
    }

    private fun pinContract(
        tenantId: UUID,
        caseId: UUID,
    ) {
        mockMvc
            .perform(
                post(CASE_RESOLUTION_CONTRACT_PATH, tenantId, caseId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONTRACT_PIN),
            ).andExpect(status().isOk)
    }

    private fun recordAndBindLockedAccount(
        tenantId: UUID,
        caseId: UUID,
    ) {
        mockMvc
            .perform(
                post(CONNECTOR_OBSERVATIONS_PATH, tenantId, caseId)
                    .header("If-Match", "\"2\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isOk)
        val observationId =
            jdbcClient
                .sql(
                    """
                    SELECT observation_id
                    FROM case_timeline_entries
                    WHERE tenant_id = :tenantId AND case_id = :caseId AND stream_version = 3
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(UUID::class.java)
                .single()
        mockMvc
            .perform(
                post(ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId)
                    .header("If-Match", "\"3\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"observationId":"$observationId","state":"LOCKED"}"""),
            ).andExpect(status().isOk)
    }

    private fun createApprovalRequest(
        tenantId: UUID,
        runId: UUID,
    ): UUID {
        val response =
            mockMvc
                .perform(post(APPROVAL_REQUESTS_PATH, tenantId, runId))
                .andExpect(status().isCreated)
                .andExpect(header().exists("Location"))
                .andReturn()
        return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun caseIdForRun(
        tenantId: UUID,
        runId: UUID,
    ): UUID =
        jdbcClient
            .sql("SELECT case_id FROM resolution_runs WHERE tenant_id = :tenantId AND run_id = :runId")
            .param("tenantId", tenantId)
            .param("runId", runId)
            .query(UUID::class.java)
            .single()

    private fun decisionIdForRequest(
        tenantId: UUID,
        requestId: UUID,
    ): UUID =
        jdbcClient
            .sql(
                """
                SELECT approval_decision_id
                FROM resolution_approval_decisions
                WHERE tenant_id = :tenantId AND approval_request_id = :requestId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("requestId", requestId)
            .query(UUID::class.java)
            .single()

    private fun authorizationGrantIdForDecision(
        tenantId: UUID,
        decisionId: UUID,
    ): UUID =
        jdbcClient
            .sql(
                """
                SELECT authorization_grant_id
                FROM capability_authorization_grants
                WHERE tenant_id = :tenantId AND approval_decision_id = :decisionId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("decisionId", decisionId)
            .query(UUID::class.java)
            .single()

    private fun authorizationConsumptionIdForGrant(
        tenantId: UUID,
        grantId: UUID,
    ): UUID =
        jdbcClient
            .sql(
                """
                SELECT authorization_consumption_id
                FROM capability_authorization_consumptions
                WHERE tenant_id = :tenantId AND authorization_grant_id = :grantId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("grantId", grantId)
            .query(UUID::class.java)
            .single()

    private fun insertCapabilityRoute(tenantId: UUID) {
        jdbcClient
            .sql(
                """
                INSERT INTO tenant_capability_routes (tenant_id, capability, connector)
                VALUES (:tenantId, 'identity.account.unlock', 'identity-stub')
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .update()
    }

    private fun assertCapabilityRouteImmutable(tenantId: UUID) {
        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE tenant_capability_routes
                    SET connector = connector
                    WHERE tenant_id = :tenantId AND capability = 'identity.account.unlock'
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun requestExpiresAt(
        tenantId: UUID,
        requestId: UUID,
    ): Instant =
        jdbcClient
            .sql(
                """
                SELECT expires_at
                FROM resolution_approval_requests
                WHERE tenant_id = :tenantId AND approval_request_id = :requestId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("requestId", requestId)
            .query(OffsetDateTime::class.java)
            .single()
            .toInstant()

    private fun registerActor(
        tenantId: UUID,
        subject: String,
    ): UUID {
        val response =
            mockMvc
                .perform(
                    post(HUMAN_ACTORS_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"identityProvider":"workforce-sso","subject":"$subject"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun attestRequesterAuthority(
        tenantId: UUID,
        actorId: UUID,
        caseId: UUID,
    ): UUID {
        val expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES)
        val response =
            mockMvc
                .perform(
                    post(AUTHORITY_EVIDENCE_PATH, tenantId, actorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "authority": "REQUESTER",
                              "caseId": "$caseId",
                              "sourceProvider": "workforce-sso",
                              "sourceReference": "claims/$actorId",
                              "expiresAt": "$expiresAt"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun insertExpiredRequesterAuthority(
        tenantId: UUID,
        actorId: UUID,
        caseId: UUID,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        jdbcClient
            .sql(
                """
                INSERT INTO approval_authority_evidence (
                    tenant_id, evidence_id, actor_id, authority, case_id,
                    source_provider, source_reference, attested_at, expires_at
                ) VALUES (
                    :tenantId, :evidenceId, :actorId, 'REQUESTER', :caseId,
                    'workforce-sso', 'claims/expired', :attestedAt, :expiresAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("evidenceId", UUID.randomUUID())
            .param("actorId", actorId)
            .param("caseId", caseId)
            .param("attestedAt", now.minusMinutes(20))
            .param("expiresAt", now.minusMinutes(10))
            .update()
    }

    private fun insertExpiredApprovalRequest(
        tenantId: UUID,
        runId: UUID,
    ): UUID {
        val requestId = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_approval_requests (
                    tenant_id, approval_request_id, run_id, step_id,
                    required_authority, requested_at, expires_at
                ) VALUES (
                    :tenantId, :requestId, :runId, 'unlock-account',
                    'REQUESTER', clock_timestamp() - INTERVAL '30 minutes',
                    clock_timestamp() - INTERVAL '15 minutes'
                )
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("requestId", requestId)
            .param("runId", runId)
            .update()
        return requestId
    }

    private fun decide(
        tenantId: UUID,
        requestId: UUID,
        subject: String,
        outcome: String,
    ) = post(APPROVAL_DECISION_PATH, tenantId, requestId)
        .with(
            jwt().jwt {
                it.issuer(TRUSTED_ISSUER)
                it.subject(subject)
            },
        ).contentType(MediaType.APPLICATION_JSON)
        .content("""{"outcome":"$outcome"}""")

    companion object {
        private const val CASE_PATH = "/api/v1/tenants/{tenantId}/cases"
        private const val CASE_RESOLUTION_CONTRACT_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-contract"
        private const val CASE_RESOLUTION_RUNS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-runs"
        private const val APPROVAL_REQUESTS_PATH =
            "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/approval-requests"
        private const val APPROVAL_DECISION_PATH =
            "/api/v1/tenants/{tenantId}/approval-requests/{requestId}/decision"
        private const val AUTHORIZATION_GRANTS_PATH =
            "/internal/v1/tenants/{tenantId}/approval-decisions/{decisionId}/authorization-grants"
        private const val AUTHORIZATION_CONSUMPTIONS_PATH =
            "/internal/v1/tenants/{tenantId}/capability-authorization-grants/{grantId}/consumptions"
        private const val HUMAN_ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val AUTHORITY_EVIDENCE_PATH =
            "$HUMAN_ACTORS_PATH/{actorId}/approval-authority-evidence"
        private const val CONNECTOR_OBSERVATIONS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations"
        private const val ACCOUNT_ACCESS_FACTS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states"
        private const val CONTRACT_REVISIONS_PATH = "/internal/v1/tenants/{tenantId}/resolution-contracts"
        private const val TRUSTED_ISSUER = "https://identity.example.test"
        private const val OPEN_CASE =
            """{"goal":"Restore workspace access","initialObservation":"I cannot sign in."}"""
        private const val APPROVED_DECISION = """{"outcome":"APPROVED"}"""
        private const val CONTRACT_PIN = """{"key":"restore-workspace-access","revision":1}"""
        private const val CONNECTOR_OBSERVATION =
            """{"connector":"identity-stub","reference":"accounts/customer-42","content":"account state observed"}"""
        private val VALID_CONTRACT =
            requireNotNull(
                ApprovalDecisionApiIntegrationTest::class.java
                    .getResource("/contracts/restore-workspace-access.yaml"),
            ).readText()

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
