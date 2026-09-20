package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `authenticated actor records one immutable decision with current requester evidence`() {
        val tenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)
        val caseId = caseIdForRun(tenantId, runId)
        val requestId = createApprovalRequest(tenantId, runId)
        val actorId = registerActor(mockMvc, tenantId, "employee-42")
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
        val consumptionId = consumeAndAssertAuthorizationGrant(tenantId, grantId, runId, caseId)
        expectCapabilityResultReceiptMissing(mockMvc, tenantId, runId)
        invokeAndAssertConsumption(
            mockMvc,
            jdbcClient,
            CapabilityInvocationTestContext(tenantId, consumptionId, grantId, runId, caseId),
        )
        expectOutcomeAssessmentNotVerifying(mockMvc, tenantId, runId)
        recordAndAssertCapabilityResult(
            mockMvc,
            jdbcClient,
            CapabilityResultTestContext(tenantId, runId, consumptionId, caseId),
        )
        assessAndAssertOutcomeProof(mockMvc, jdbcClient, tenantId, runId, caseId)

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
        val actorId = registerActor(mockMvc, tenantId, "employee-99")
        val otherTenantId = UUID.randomUUID()
        registerActor(mockMvc, otherTenantId, "employee-99")

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
        val actorId = registerActor(mockMvc, tenantId, "employee-authorization")
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

    @Test
    fun `invocation fails closed when the configured connector is not installed`() {
        val tenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)
        val caseId = caseIdForRun(tenantId, runId)
        val requestId = createApprovalRequest(tenantId, runId)
        val actorId = registerActor(mockMvc, tenantId, "employee-unavailable-connector")
        attestRequesterAuthority(tenantId, actorId, caseId)
        mockMvc
            .perform(decide(tenantId, requestId, "employee-unavailable-connector", "APPROVED"))
            .andExpect(status().isCreated)
        val decisionId = decisionIdForRequest(tenantId, requestId)
        val grantId = authorizationGrantIdFromCreatedGrant(tenantId, decisionId)
        insertCapabilityRoute(tenantId, connector = "uninstalled-identity")
        mockMvc
            .perform(post(AUTHORIZATION_CONSUMPTIONS_PATH, tenantId, grantId))
            .andExpect(status().isCreated)
        val consumptionId = authorizationConsumptionIdForGrant(tenantId, grantId)

        mockMvc
            .perform(post(CAPABILITY_INVOCATIONS_PATH, tenantId, consumptionId))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:capability-connector-unavailable"))
            .andExpect(jsonPath("$.connector").value("uninstalled-identity"))
        val receiptCount =
            jdbcClient
                .sql(
                    """
                    SELECT count(*)
                    FROM capability_invocation_receipts
                    WHERE tenant_id = :tenantId AND authorization_consumption_id = :consumptionId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("consumptionId", consumptionId)
                .query(Long::class.java)
                .single()
        assertThat(receiptCount).isZero()
    }

    @Test
    fun `failed action starts one explicit successor with fresh authorization requirements`() {
        val tenantId = UUID.randomUUID()
        val retrySubject = "employee-retry"
        val failedRunId = prepareResolutionRun(tenantId)
        val caseId = caseIdForRun(tenantId, failedRunId)
        val requestId = createApprovalRequest(tenantId, failedRunId)
        val actorId = registerActor(mockMvc, tenantId, retrySubject)
        attestRequesterAuthority(tenantId, actorId, caseId)
        mockMvc
            .perform(decide(tenantId, requestId, retrySubject, "APPROVED"))
            .andExpect(status().isCreated)
        val decisionId = decisionIdForRequest(tenantId, requestId)
        val grantId = createAndAssertAuthorizationGrant(tenantId, decisionId, requestId, failedRunId, caseId)
        val consumptionId =
            consumeAndAssertAuthorizationGrant(
                tenantId,
                grantId,
                failedRunId,
                caseId,
                connector = "identity-stub-failure",
            )

        mockMvc
            .perform(post(CAPABILITY_INVOCATIONS_PATH, tenantId, consumptionId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.outcome").value("FAILED"))
            .andExpect(jsonPath("$.connector").value("identity-stub-failure"))
        mockMvc
            .perform(post(RUN_CAPABILITY_RESULTS_PATH, tenantId, failedRunId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.toState").value("ACTION_FAILED"))

        val retry =
            assertAuthorizedRetryBoundary(
                mockMvc,
                jdbcClient,
                RetryAuthorityBoundaryContext(tenantId, failedRunId, caseId, retrySubject, actorId),
            )
        assertFailedSuccessorCannotRetry(tenantId, retry, caseId, retrySubject)
    }

    private fun assertFailedSuccessorCannotRetry(
        tenantId: UUID,
        retry: AuthorizedRetry,
        caseId: UUID,
        subject: String,
    ) {
        val replacementRunId = retry.replacementRunId
        val requestId = createApprovalRequest(tenantId, replacementRunId)
        mockMvc.perform(decide(tenantId, requestId, subject, "APPROVED")).andExpect(status().isCreated)
        val decisionId = decisionIdForRequest(tenantId, requestId)
        val grantId = createAndAssertAuthorizationGrant(tenantId, decisionId, requestId, replacementRunId, caseId)
        mockMvc.perform(post(AUTHORIZATION_CONSUMPTIONS_PATH, tenantId, grantId)).andExpect(status().isCreated)
        val consumptionId = authorizationConsumptionIdForGrant(tenantId, grantId)
        mockMvc
            .perform(post(CAPABILITY_INVOCATIONS_PATH, tenantId, consumptionId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.outcome").value("FAILED"))
        mockMvc
            .perform(post(RUN_CAPABILITY_RESULTS_PATH, tenantId, replacementRunId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.toState").value("ACTION_FAILED"))

        assertDatabaseRejectsExhaustedEligibility(jdbcClient, transactions, tenantId, retry)

        mockMvc
            .perform(retry(tenantId, replacementRunId, subject, 4))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-retry-attempt-limit-reached"))
            .andExpect(jsonPath("$.policyRevision").value(RETRY_POLICY_REVISION))
            .andExpect(jsonPath("$.sourceAttemptNumber").value(2))
            .andExpect(jsonPath("$.maximumAttempts").value(2))
            .andExpect(jsonPath("$.reason").value("ATTEMPT_LIMIT_REACHED"))
        assertThat(runCountForCase(jdbcClient, tenantId, caseId)).isEqualTo(2)
        assertThat(runStateAndVersion(jdbcClient, tenantId, replacementRunId)).isEqualTo("ACTION_FAILED" to 1L)

        assertExhaustedRunEscalation(
            mockMvc,
            jdbcClient,
            ExhaustedRunEscalationContext(
                tenantId,
                replacementRunId,
                caseId,
                subject,
                retry.actorId,
                retry.authorityEvidenceId,
            ),
        )
    }

    private fun createAndAssertAuthorizationGrant(
        tenantId: UUID,
        decisionId: UUID,
        requestId: UUID,
        runId: UUID,
        caseId: UUID,
    ): UUID =
        createAndAssertAuthorizationGrant(
            mockMvc,
            jdbcClient,
            AuthorizationGrantTestContext(tenantId, decisionId, requestId, runId, caseId),
        )

    private fun consumeAndAssertAuthorizationGrant(
        tenantId: UUID,
        grantId: UUID,
        runId: UUID,
        caseId: UUID,
        connector: String = "identity-stub",
    ): UUID {
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

        insertCapabilityRoute(tenantId, connector)
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
            .andExpect(jsonPath("$.connector").value(connector))
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
        return consumptionId
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

    private fun authorizationGrantIdFromCreatedGrant(
        tenantId: UUID,
        decisionId: UUID,
    ): UUID {
        mockMvc
            .perform(post(AUTHORIZATION_GRANTS_PATH, tenantId, decisionId))
            .andExpect(status().isCreated)
        return authorizationGrantIdForDecision(jdbcClient, tenantId, decisionId)
    }

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

    private fun insertCapabilityRoute(
        tenantId: UUID,
        connector: String = "identity-stub",
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO tenant_capability_routes (tenant_id, capability, connector)
                VALUES (:tenantId, 'identity.account.unlock', :connector)
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("connector", connector)
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
private const val CAPABILITY_INVOCATIONS_PATH =
    "/internal/v1/tenants/{tenantId}/capability-authorization-consumptions/{consumptionId}/invocations"
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
private const val RUN_CAPABILITY_RESULTS_PATH =
    "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/capability-results"
private const val RUN_RETRIES_PATH =
    "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/retries"
private const val RUN_ESCALATIONS_PATH =
    "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/escalations"
private const val RUN_OUTCOME_PROOF_PATH =
    "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/outcome-proof"
private const val RUN_OUTCOME_PROOF_ACCEPTANCES_PATH =
    "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/outcome-proof-acceptances"

private data class CapabilityResultTestContext(
    val tenantId: UUID,
    val runId: UUID,
    val consumptionId: UUID,
    val caseId: UUID,
)

private data class CapabilityInvocationTestContext(
    val tenantId: UUID,
    val consumptionId: UUID,
    val grantId: UUID,
    val runId: UUID,
    val caseId: UUID,
)

private fun expectOutcomeAssessmentNotVerifying(
    mockMvc: MockMvc,
    tenantId: UUID,
    runId: UUID,
) {
    mockMvc
        .perform(get(RUN_OUTCOME_PROOF_PATH, tenantId, runId))
        .andExpect(status().isConflict)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-verifying"))
        .andExpect(jsonPath("$.state").value("WAITING_FOR_APPROVAL"))
    mockMvc
        .perform(post(RUN_OUTCOME_PROOF_ACCEPTANCES_PATH, tenantId, runId))
        .andExpect(status().isConflict)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-verifying"))
        .andExpect(jsonPath("$.state").value("WAITING_FOR_APPROVAL"))
}

private fun assessAndAssertOutcomeProof(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
    caseId: UUID,
) = OutcomeProofApiFixture(mockMvc, jdbcClient, tenantId, runId, caseId).assertScenario()

private class OutcomeProofApiFixture(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
    private val tenantId: UUID,
    private val runId: UUID,
    private val caseId: UUID,
) {
    fun assertScenario() {
        mockMvc
            .perform(get(RUN_OUTCOME_PROOF_PATH, UUID.randomUUID(), runId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-found"))
        expectPending(expectedVersion = 4, reason = "ELIGIBLE_EVIDENCE_MISSING")

        val observationId = recordObservation(expectedVersion = 4, state = "ACTIVE")
        expectPending(expectedVersion = 5, reason = "ELIGIBLE_EVIDENCE_MISSING")
        bindFact(observationId, expectedVersion = 5, state = "ACTIVE")
        expectAccepted(observationId, expectedVersion = 6)

        val mismatchId = recordObservation(expectedVersion = 6, state = "LOCKED")
        bindFact(mismatchId, expectedVersion = 7, state = "LOCKED")
        expectPending(expectedVersion = 8, reason = "VALUE_MISMATCH", actualValue = "LOCKED")
        expectAcceptancePending(expectedVersion = 8, reason = "VALUE_MISMATCH")
        assertThat(runState(jdbcClient, tenantId, runId)).isEqualTo("VERIFYING:1")
        assertThat(caseStatus(jdbcClient, tenantId, caseId)).isEqualTo("OPEN")

        val finalObservationId = recordObservation(expectedVersion = 8, state = "ACTIVE")
        bindFact(finalObservationId, expectedVersion = 9, state = "ACTIVE")
        expectAccepted(finalObservationId, expectedVersion = 10)
        acceptAndAssert(finalObservationId)
    }

    private fun expectPending(
        expectedVersion: Long,
        reason: String,
        actualValue: String? = null,
    ) {
        val result =
            mockMvc
                .perform(get(RUN_OUTCOME_PROOF_PATH, tenantId, runId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingReason").value(reason))
                .andExpect(jsonPath("$.caseStreamVersion").value(expectedVersion))
                .andExpect(jsonPath("$.fact").value("account.access.state"))
                .andExpect(jsonPath("$.expectedValue").value("ACTIVE"))
        if (actualValue == null) {
            result.andExpect(jsonPath("$.evidence").doesNotExist())
        } else {
            result.andExpect(jsonPath("$.evidence.actualValue").value(actualValue))
        }
    }

    private fun expectAccepted(
        observationId: UUID,
        expectedVersion: Long,
    ) {
        mockMvc
            .perform(get(RUN_OUTCOME_PROOF_PATH, tenantId, runId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.pendingReason").doesNotExist())
            .andExpect(jsonPath("$.caseStreamVersion").value(expectedVersion))
            .andExpect(jsonPath("$.evidence.observationId").value(observationId.toString()))
            .andExpect(jsonPath("$.evidence.observationStreamVersion").value(expectedVersion - 1))
            .andExpect(jsonPath("$.evidence.factStreamVersion").value(expectedVersion))
            .andExpect(jsonPath("$.evidence.actualValue").value("ACTIVE"))
            .andExpect(jsonPath("$.evidence.observedAt").exists())
            .andExpect(jsonPath("$.evidence.boundAt").exists())
    }

    private fun expectAcceptancePending(
        expectedVersion: Long,
        reason: String,
    ) {
        mockMvc
            .perform(post(RUN_OUTCOME_PROOF_ACCEPTANCES_PATH, tenantId, runId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-outcome-proof-pending"))
            .andExpect(jsonPath("$.reason").value(reason))
            .andExpect(jsonPath("$.caseStreamVersion").value(expectedVersion))
    }

    private fun acceptAndAssert(observationId: UUID) {
        mockMvc
            .perform(post(RUN_OUTCOME_PROOF_ACCEPTANCES_PATH, UUID.randomUUID(), runId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-found"))

        mockMvc
            .perform(post(RUN_OUTCOME_PROOF_ACCEPTANCES_PATH, tenantId, runId))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andExpect(jsonPath("$.sequence").value(2))
            .andExpect(jsonPath("$.eventType").value("OUTCOME_PROOF_ACCEPTED"))
            .andExpect(jsonPath("$.fromState").value("VERIFYING"))
            .andExpect(jsonPath("$.toState").value("VERIFIED_RESOLVED"))
            .andExpect(jsonPath("$.runCaseStreamVersion").value(4))
            .andExpect(jsonPath("$.caseStreamVersion").value(10))
            .andExpect(jsonPath("$.fact").value("account.access.state"))
            .andExpect(jsonPath("$.expectedValue").value("ACTIVE"))
            .andExpect(jsonPath("$.observationId").value(observationId.toString()))
            .andExpect(jsonPath("$.observationStreamVersion").value(9))
            .andExpect(jsonPath("$.factStreamVersion").value(10))
            .andExpect(jsonPath("$.acceptedAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())
        val eventId = acceptedProofEventId()

        mockMvc
            .perform(post(RUN_OUTCOME_PROOF_ACCEPTANCES_PATH, tenantId, runId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(eventId.toString()))
            .andExpect(jsonPath("$.toState").value("VERIFIED_RESOLVED"))
        assertThat(runState(jdbcClient, tenantId, runId)).isEqualTo("VERIFIED_RESOLVED:2")
        assertThat(caseStatus(jdbcClient, tenantId, caseId)).isEqualTo("VERIFIED_RESOLVED")
        assertThat(caseStreamVersion(jdbcClient, tenantId, caseId)).isEqualTo(11)
        assertThat(latestCaseEventType()).isEqualTo("CaseVerifiedResolved")
        assertAcceptanceImmutable()

        mockMvc
            .perform(get(RUN_OUTCOME_PROOF_PATH, tenantId, runId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.state").value("VERIFIED_RESOLVED"))
        mockMvc
            .perform(
                post(CONNECTOR_OBSERVATIONS_PATH, tenantId, caseId)
                    .header("If-Match", "\"11\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-case-command"))
    }

    private fun acceptedProofEventId(): UUID =
        jdbcClient
            .sql(
                """
                SELECT run_event_id
                FROM resolution_outcome_proof_acceptances
                WHERE tenant_id = :tenantId AND run_id = :runId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("runId", runId)
            .query(UUID::class.java)
            .single()

    private fun latestCaseEventType(): String =
        jdbcClient
            .sql(
                """
                SELECT event_type
                FROM case_events
                WHERE tenant_id = :tenantId AND case_id = :caseId
                ORDER BY stream_version DESC
                LIMIT 1
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("caseId", caseId)
            .query(String::class.java)
            .single()

    private fun assertAcceptanceImmutable() {
        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_outcome_proof_acceptances
                    SET expected_value = expected_value
                    WHERE tenant_id = :tenantId AND run_id = :runId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("runId", runId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun recordObservation(
        expectedVersion: Long,
        state: String,
    ): UUID {
        val streamVersion = expectedVersion + 1
        mockMvc
            .perform(
                post(CONNECTOR_OBSERVATIONS_PATH, tenantId, caseId)
                    .header("If-Match", "\"$expectedVersion\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "connector": "login-probe-stub",
                          "reference": "accounts/customer-42",
                          "content": "post-action login probe reports $state"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
        return jdbcClient
            .sql(
                """
                SELECT observation_id
                FROM case_timeline_entries
                WHERE tenant_id = :tenantId AND case_id = :caseId AND stream_version = :streamVersion
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("caseId", caseId)
            .param("streamVersion", streamVersion)
            .query(UUID::class.java)
            .single()
    }

    private fun bindFact(
        observationId: UUID,
        expectedVersion: Long,
        state: String,
    ) {
        mockMvc
            .perform(
                post(ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId)
                    .header("If-Match", "\"$expectedVersion\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"observationId":"$observationId","state":"$state"}"""),
            ).andExpect(status().isOk)
    }
}

private fun registerActor(
    mockMvc: MockMvc,
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

private const val RETRY_POLICY_REVISION = "ergon.dev/policy/resolution-retry/v1"
private const val FOLLOW_UP_QUEUE_KEY = "access-restoration"

private data class AuthorizationGrantTestContext(
    val tenantId: UUID,
    val decisionId: UUID,
    val requestId: UUID,
    val runId: UUID,
    val caseId: UUID,
)

private fun createAndAssertAuthorizationGrant(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: AuthorizationGrantTestContext,
): UUID {
    mockMvc
        .perform(post(AUTHORIZATION_GRANTS_PATH, context.tenantId, context.decisionId))
        .andExpect(status().isCreated)
        .andExpect(jsonPath("$.approvalDecisionId").value(context.decisionId.toString()))
        .andExpect(jsonPath("$.approvalRequestId").value(context.requestId.toString()))
        .andExpect(jsonPath("$.runId").value(context.runId.toString()))
        .andExpect(jsonPath("$.caseId").value(context.caseId.toString()))
        .andExpect(jsonPath("$.policyRevision").value("ergon.dev/policy/access-restoration/v1"))
        .andExpect(jsonPath("$.stepId").value("unlock-account"))
        .andExpect(jsonPath("$.capability").value("identity.account.unlock"))
        .andExpect(jsonPath("$.authorizedAt").exists())
        .andExpect(
            jsonPath("$.expiresAt").value(requestExpiresAt(jdbcClient, context.tenantId, context.requestId).toString()),
        ).andExpect(jsonPath("$.recordedAt").exists())
    val grantId = authorizationGrantIdForDecision(jdbcClient, context.tenantId, context.decisionId)

    mockMvc
        .perform(post(AUTHORIZATION_GRANTS_PATH, context.tenantId, context.decisionId))
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
            ).param("tenantId", context.tenantId)
            .param("grantId", grantId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
    return grantId
}

private fun authorizationGrantIdForDecision(
    jdbcClient: JdbcClient,
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

private fun requestExpiresAt(
    jdbcClient: JdbcClient,
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

private data class AuthorizedRetry(
    val replacementRunId: UUID,
    val actorId: UUID,
    val authorityEvidenceId: UUID,
)

private fun assertDatabaseRejectsExhaustedEligibility(
    jdbcClient: JdbcClient,
    transactions: TransactionTemplate,
    tenantId: UUID,
    retry: AuthorizedRetry,
) {
    transactions.executeWithoutResult { transaction ->
        val thirdRunId = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_runs (
                    tenant_id, run_id, case_id, case_stream_version,
                    contract_key, contract_revision, policy_revision,
                    step_id, capability, effective_risk, required_approval,
                    initial_state, attempt_number, predecessor_run_id
                )
                SELECT
                    tenant_id, :thirdRunId, case_id, case_stream_version,
                    contract_key, contract_revision, policy_revision,
                    step_id, capability, effective_risk, required_approval,
                    initial_state, 3, run_id
                FROM resolution_runs
                WHERE tenant_id = :tenantId AND run_id = :replacementRunId
                """.trimIndent(),
            ).param("thirdRunId", thirdRunId)
            .param("tenantId", tenantId)
            .param("replacementRunId", retry.replacementRunId)
            .update()

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_run_events (
                        tenant_id, run_id, sequence, event_id, event_type,
                        from_state, to_state, replacement_run_id, occurred_at,
                        retry_actor_id, retry_authority_evidence_id,
                        retry_policy_revision, retry_source_attempt_number,
                        retry_maximum_attempts
                    ) VALUES (
                        :tenantId, :runId, 2, :eventId, 'RETRY_STARTED',
                        'ACTION_FAILED', 'SUPERSEDED', :replacementRunId, :occurredAt,
                        :actorId, :authorityEvidenceId,
                        :policyRevision, 2, 2
                    )
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("runId", retry.replacementRunId)
                .param("eventId", UUID.randomUUID())
                .param("replacementRunId", thirdRunId)
                .param("occurredAt", OffsetDateTime.now(ZoneOffset.UTC))
                .param("actorId", retry.actorId)
                .param("authorityEvidenceId", retry.authorityEvidenceId)
                .param("policyRevision", RETRY_POLICY_REVISION)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("ck_resolution_run_events_retry_eligibility")
        transaction.setRollbackOnly()
    }
}

private fun assertAuthorizedRetryBoundary(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: RetryAuthorityBoundaryContext,
): AuthorizedRetry {
    mockMvc
        .perform(post(RUN_RETRIES_PATH, context.tenantId, context.failedRunId).header("If-Match", "\"4\""))
        .andExpect(status().isUnauthorized)
    expectRecoveryAuthorityNotFound(mockMvc, context.tenantId, context.failedRunId, context.subject)
    mockMvc
        .perform(escalate(context.tenantId, context.failedRunId, context.subject))
        .andExpect(status().isForbidden)
    insertExpiredResolverAuthority(jdbcClient, context.tenantId, context.actorId)
    expectRecoveryAuthorityNotFound(mockMvc, context.tenantId, context.failedRunId, context.subject)

    val resolverEvidenceId = attestResolverAuthority(mockMvc, context.tenantId, context.actorId)
    mockMvc
        .perform(escalate(context.tenantId, context.failedRunId, context.subject))
        .andExpect(status().isConflict)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-retry-budget-available"))
    val otherTenantId = UUID.randomUUID()
    val otherTenantActorId = registerActor(mockMvc, otherTenantId, context.subject)
    attestResolverAuthority(mockMvc, otherTenantId, otherTenantActorId)
    mockMvc
        .perform(escalate(otherTenantId, context.failedRunId, context.subject))
        .andExpect(status().isNotFound)
    val replacementRunId =
        assertExplicitRetry(
            mockMvc,
            jdbcClient,
            FailedRunRetryContext(
                context.tenantId,
                context.failedRunId,
                context.caseId,
                context.subject,
                context.actorId,
                resolverEvidenceId,
                otherTenantId,
            ),
        )
    return AuthorizedRetry(replacementRunId, context.actorId, resolverEvidenceId)
}

private data class RetryAuthorityBoundaryContext(
    val tenantId: UUID,
    val failedRunId: UUID,
    val caseId: UUID,
    val subject: String,
    val actorId: UUID,
)

private data class ExhaustedRunEscalationContext(
    val tenantId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val subject: String,
    val actorId: UUID,
    val authorityEvidenceId: UUID,
)

private fun assertExhaustedRunEscalation(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: ExhaustedRunEscalationContext,
) {
    val followUp = createExhaustedRunEscalation(mockMvc, context)
    assertFollowUpRetrieval(mockMvc, jdbcClient, context, followUp)
    assertFollowUpClaim(mockMvc, jdbcClient, context, followUp)
    assertEscalationReplay(mockMvc, context, followUp)
    assertEscalationPersistence(jdbcClient, context, followUp.workItemId)
}

private fun assertFollowUpClaim(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
) {
    val claimsLocation = "${followUp.location}/claims"
    mockMvc.perform(post(claimsLocation)).andExpect(status().isUnauthorized)
    val creation =
        mockMvc
            .perform(post(claimsLocation).with(humanJwt(context.subject)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.claimId").isNotEmpty)
            .andExpect(jsonPath("$.workItemId").value(followUp.workItemId.toString()))
            .andExpect(jsonPath("$.resolverActorId").value(context.actorId.toString()))
            .andExpect(jsonPath("$.authorityEvidenceId").value(context.authorityEvidenceId.toString()))
            .andExpect(jsonPath("$.claimedAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())
            .andReturn()
    val claimLocation = requireNotNull(creation.response.getHeader("Location"))
    val claimId = UUID.fromString(claimLocation.substringAfterLast('/'))

    mockMvc.perform(get(claimLocation)).andExpect(status().isUnauthorized)
    mockMvc
        .perform(get(claimLocation).with(humanJwt(context.subject)))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.claimId").value(claimId.toString()))
        .andExpect(jsonPath("$.workItemId").value(followUp.workItemId.toString()))
    mockMvc
        .perform(post(claimsLocation).with(humanJwt(context.subject)))
        .andExpect(status().isOk)
        .andExpect(header().string("Location", claimLocation))
        .andExpect(jsonPath("$.claimId").value(claimId.toString()))

    assertExclusiveResolverOwnership(mockMvc, jdbcClient, context, followUp, claimId)

    mockMvc
        .perform(get(followUp.location.substringBeforeLast('/')).with(humanJwt(context.subject)))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)
    assertThatThrownBy {
        jdbcClient
            .sql(
                """
                UPDATE human_follow_up_claims
                SET resolver_actor_id = resolver_actor_id
                WHERE tenant_id = :tenantId AND claim_id = :claimId
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("claimId", claimId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
}

private fun assertExclusiveResolverOwnership(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
    claimId: UUID,
) {
    val claimsLocation = "${followUp.location}/claims"
    val competingSubject = "follow-up-competitor-${UUID.randomUUID()}"
    val competingActorId = registerActor(mockMvc, context.tenantId, competingSubject)
    attestResolverAuthority(mockMvc, context.tenantId, competingActorId)
    mockMvc
        .perform(post(claimsLocation).with(humanJwt(competingSubject)))
        .andExpect(status().isConflict)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-already-claimed"))

    val ownedLocation = "${followUp.location.substringBeforeLast('/')}/owned"
    mockMvc.perform(get(ownedLocation)).andExpect(status().isUnauthorized)
    mockMvc
        .perform(get(ownedLocation).param("limit", "1").with(humanJwt(context.subject)))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].workItem.workItemId").value(followUp.workItemId.toString()))
        .andExpect(jsonPath("$.items[0].workItem.caseId").value(context.caseId.toString()))
        .andExpect(jsonPath("$.items[0].workItem.queueKey").value(FOLLOW_UP_QUEUE_KEY))
        .andExpect(jsonPath("$.items[0].claim.claimId").value(claimId.toString()))
        .andExpect(jsonPath("$.items[0].claim.resolverActorId").value(context.actorId.toString()))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
    mockMvc
        .perform(get(ownedLocation).with(humanJwt(competingSubject)))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)

    val observerSubject = "owned-follow-up-observer-${UUID.randomUUID()}"
    registerActor(mockMvc, context.tenantId, observerSubject)
    mockMvc
        .perform(get(ownedLocation).with(humanJwt(observerSubject)))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)

    val claimedAt = followUpClaimedAt(jdbcClient, context.tenantId, claimId)
    mockMvc
        .perform(
            get(ownedLocation)
                .param("afterClaimedAt", claimedAt.toString())
                .param("afterClaimId", claimId.toString())
                .with(humanJwt(context.subject)),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
    mockMvc
        .perform(
            get(ownedLocation)
                .param("afterClaimedAt", claimedAt.toString())
                .with(humanJwt(context.subject)),
        ).andExpect(status().isBadRequest)
        .andExpect(
            jsonPath("$.type")
                .value("urn:ergon:problem:invalid-resolver-owned-human-follow-up-page"),
        )
}

private fun followUpClaimedAt(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    claimId: UUID,
): Instant =
    jdbcClient
        .sql(
            """
            SELECT claimed_at
            FROM human_follow_up_claims
            WHERE tenant_id = :tenantId AND claim_id = :claimId
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("claimId", claimId)
        .query(OffsetDateTime::class.java)
        .single()
        .toInstant()

private fun humanJwt(subject: String) =
    jwt().jwt {
        it.issuer(TRUSTED_ISSUER)
        it.subject(subject)
    }

private data class CreatedFollowUp(
    val workItemId: UUID,
    val location: String,
)

private fun createExhaustedRunEscalation(
    mockMvc: MockMvc,
    context: ExhaustedRunEscalationContext,
): CreatedFollowUp {
    mockMvc.perform(post(RUN_ESCALATIONS_PATH, context.tenantId, context.runId)).andExpect(status().isUnauthorized)
    val creation =
        mockMvc
            .perform(escalate(context.tenantId, context.runId, context.subject))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.runId").value(context.runId.toString()))
            .andExpect(jsonPath("$.state").value("ESCALATED"))
            .andExpect(jsonPath("$.stateVersion").value(2))
            .andExpect(jsonPath("$.reason").value("RETRY_ATTEMPT_LIMIT_REACHED"))
            .andExpect(jsonPath("$.retryPolicyRevision").value(RETRY_POLICY_REVISION))
            .andExpect(jsonPath("$.retrySourceAttemptNumber").value(2))
            .andExpect(jsonPath("$.retryMaximumAttempts").value(2))
            .andExpect(jsonPath("$.escalationEventId").isNotEmpty)
            .andExpect(jsonPath("$.followUpWorkItemId").isNotEmpty)
            .andExpect(jsonPath("$.followUpQueueKey").value(FOLLOW_UP_QUEUE_KEY))
            .andExpect(jsonPath("$.followUpStatus").value("OPEN"))
            .andReturn()
    val location = requireNotNull(creation.response.getHeader("Location"))
    return CreatedFollowUp(UUID.fromString(location.substringAfterLast('/')), location)
}

private fun assertFollowUpRetrieval(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
) {
    val inboxLocation = followUp.location.substringBeforeLast('/')
    mockMvc.perform(get(followUp.location)).andExpect(status().isUnauthorized)
    mockMvc.perform(get(inboxLocation)).andExpect(status().isUnauthorized)
    assertAuthorizedFollowUpRetrieval(mockMvc, context, followUp)
    assertAuthorizedInboxRetrieval(mockMvc, context, followUp, inboxLocation, followUpOpenedAt(jdbcClient, context))
    assertUnauthorizedResolverSeesNoFollowUp(mockMvc, context, followUp, inboxLocation)
}

private fun assertAuthorizedFollowUpRetrieval(
    mockMvc: MockMvc,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
) {
    mockMvc
        .perform(
            get(followUp.location).with(
                jwt().jwt {
                    it.issuer(TRUSTED_ISSUER)
                    it.subject(context.subject)
                },
            ),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.workItemId").value(followUp.workItemId.toString()))
        .andExpect(jsonPath("$.caseId").value(context.caseId.toString()))
        .andExpect(jsonPath("$.runId").value(context.runId.toString()))
        .andExpect(jsonPath("$.escalationEventId").isNotEmpty)
        .andExpect(jsonPath("$.reason").value("RETRY_ATTEMPT_LIMIT_REACHED"))
        .andExpect(jsonPath("$.queueKey").value(FOLLOW_UP_QUEUE_KEY))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.openedAt").exists())
        .andExpect(jsonPath("$.recordedAt").exists())
}

private fun assertAuthorizedInboxRetrieval(
    mockMvc: MockMvc,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
    inboxLocation: String,
    openedAt: Instant,
) {
    mockMvc
        .perform(
            get(inboxLocation)
                .param("limit", "1")
                .with(
                    jwt().jwt {
                        it.issuer(TRUSTED_ISSUER)
                        it.subject(context.subject)
                    },
                ),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].workItemId").value(followUp.workItemId.toString()))
        .andExpect(jsonPath("$.items[0].caseId").value(context.caseId.toString()))
        .andExpect(jsonPath("$.items[0].queueKey").value(FOLLOW_UP_QUEUE_KEY))
        .andExpect(jsonPath("$.items[0].status").value("OPEN"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
    assertQueueFilteredInbox(mockMvc, context, followUp, inboxLocation)
    mockMvc
        .perform(
            get(inboxLocation)
                .param("limit", "0")
                .with(
                    jwt().jwt {
                        it.issuer(TRUSTED_ISSUER)
                        it.subject(context.subject)
                    },
                ),
        ).andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-page"))
    mockMvc
        .perform(
            get(inboxLocation)
                .param("afterOpenedAt", openedAt.toString())
                .param("afterWorkItemId", followUp.workItemId.toString())
                .with(
                    jwt().jwt {
                        it.issuer(TRUSTED_ISSUER)
                        it.subject(context.subject)
                    },
                ),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
}

private fun assertQueueFilteredInbox(
    mockMvc: MockMvc,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
    inboxLocation: String,
) {
    mockMvc
        .perform(
            get(inboxLocation)
                .param("queueKey", FOLLOW_UP_QUEUE_KEY)
                .with(humanJwt(context.subject)),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].workItemId").value(followUp.workItemId.toString()))
    mockMvc
        .perform(
            get(inboxLocation)
                .param("queueKey", "different-queue")
                .with(humanJwt(context.subject)),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)
    mockMvc
        .perform(
            get(inboxLocation)
                .param("queueKey", "Invalid Queue")
                .with(humanJwt(context.subject)),
        ).andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-follow-up-queue"))
}

private fun followUpOpenedAt(
    jdbcClient: JdbcClient,
    context: ExhaustedRunEscalationContext,
): Instant =
    jdbcClient
        .sql(
            """
            SELECT opened_at
            FROM human_follow_up_work_items
            WHERE tenant_id = :tenantId AND run_id = :runId
            """.trimIndent(),
        ).param("tenantId", context.tenantId)
        .param("runId", context.runId)
        .query(OffsetDateTime::class.java)
        .single()
        .toInstant()

private fun assertUnauthorizedResolverSeesNoFollowUp(
    mockMvc: MockMvc,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
    inboxLocation: String,
) {
    val observerSubject = "follow-up-observer-${UUID.randomUUID()}"
    registerActor(mockMvc, context.tenantId, observerSubject)
    mockMvc
        .perform(
            get(followUp.location).with(
                jwt().jwt {
                    it.issuer(TRUSTED_ISSUER)
                    it.subject(observerSubject)
                },
            ),
        ).andExpect(status().isNotFound)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-follow-up-work-item-not-found"))
    mockMvc
        .perform(
            get(inboxLocation).with(
                jwt().jwt {
                    it.issuer(TRUSTED_ISSUER)
                    it.subject(observerSubject)
                },
            ),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.items").isEmpty)
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
    mockMvc
        .perform(post("${followUp.location}/claims").with(humanJwt(observerSubject)))
        .andExpect(status().isForbidden)
        .andExpect(
            jsonPath("$.type")
                .value("urn:ergon:problem:human-follow-up-resolver-authority-required"),
        )
}

private fun assertEscalationReplay(
    mockMvc: MockMvc,
    context: ExhaustedRunEscalationContext,
    followUp: CreatedFollowUp,
) {
    mockMvc
        .perform(escalate(context.tenantId, context.runId, context.subject))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.state").value("ESCALATED"))
        .andExpect(jsonPath("$.followUpWorkItemId").value(followUp.workItemId.toString()))
        .andExpect(header().string("Location", followUp.location))
}

private fun assertEscalationPersistence(
    jdbcClient: JdbcClient,
    context: ExhaustedRunEscalationContext,
    workItemId: UUID,
) {
    val escalationCount =
        jdbcClient
            .sql(
                """
                SELECT count(*)
                FROM resolution_run_events
                WHERE tenant_id = :tenantId AND run_id = :runId
                    AND event_type = 'ESCALATION_REQUESTED'
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("runId", context.runId)
            .query(Int::class.java)
            .single()
    val followUpCount =
        jdbcClient
            .sql(
                """
                SELECT count(*)
                FROM human_follow_up_work_items
                WHERE tenant_id = :tenantId AND run_id = :runId
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("runId", context.runId)
            .query(Int::class.java)
            .single()
    val queueKey =
        jdbcClient
            .sql(
                """
                SELECT queue_key
                FROM human_follow_up_work_items
                WHERE tenant_id = :tenantId AND work_item_id = :workItemId
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("workItemId", workItemId)
            .query(String::class.java)
            .single()
    assertThat(escalationCount).isEqualTo(1)
    assertThat(followUpCount).isEqualTo(1)
    assertThat(queueKey).isEqualTo(FOLLOW_UP_QUEUE_KEY)
    assertThatThrownBy {
        jdbcClient
            .sql(
                """
                UPDATE human_follow_up_work_items
                SET status = status
                WHERE tenant_id = :tenantId AND work_item_id = :workItemId
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("workItemId", workItemId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
    assertThat(runStateAndVersion(jdbcClient, context.tenantId, context.runId)).isEqualTo("ESCALATED" to 2L)
    assertThat(runCountForCase(jdbcClient, context.tenantId, context.caseId)).isEqualTo(2)
    assertThat(caseStatus(jdbcClient, context.tenantId, context.caseId)).isEqualTo("OPEN")
}

private fun expectRecoveryAuthorityNotFound(
    mockMvc: MockMvc,
    tenantId: UUID,
    failedRunId: UUID,
    subject: String,
) {
    mockMvc
        .perform(retry(tenantId, failedRunId, subject, 4))
        .andExpect(status().isForbidden)
        .andExpect(
            jsonPath("$.type")
                .value("urn:ergon:problem:current-resolution-recovery-authority-not-found"),
        )
}

private fun attestResolverAuthority(
    mockMvc: MockMvc,
    tenantId: UUID,
    actorId: UUID,
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
                          "authority": "RESOLVER",
                          "sourceProvider": "workforce-sso",
                          "sourceReference": "groups/resolvers",
                          "expiresAt": "$expiresAt"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated)
            .andReturn()
    return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
}

private fun insertExpiredResolverAuthority(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    actorId: UUID,
) {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    jdbcClient
        .sql(
            """
            INSERT INTO approval_authority_evidence (
                tenant_id, evidence_id, actor_id, authority,
                source_provider, source_reference, attested_at, expires_at
            ) VALUES (
                :tenantId, :evidenceId, :actorId, 'RESOLVER',
                'workforce-sso', 'groups/expired-resolvers', :attestedAt, :expiresAt
            )
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("evidenceId", UUID.randomUUID())
        .param("actorId", actorId)
        .param("attestedAt", now.minusMinutes(20))
        .param("expiresAt", now.minusMinutes(10))
        .update()
}

private data class FailedRunRetryContext(
    val tenantId: UUID,
    val failedRunId: UUID,
    val caseId: UUID,
    val subject: String,
    val actorId: UUID,
    val authorityEvidenceId: UUID,
    val otherTenantId: UUID,
)

private fun assertExplicitRetry(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: FailedRunRetryContext,
): UUID {
    assertRetryRejections(mockMvc, jdbcClient, context)
    assertRetryOperationMeaningConstrained(jdbcClient, context)
    val replacementRunId = startRetryAndAssert(mockMvc, context)
    assertRetryReplay(mockMvc, context, replacementRunId)
    assertRetryPersistence(jdbcClient, context, replacementRunId)
    return replacementRunId
}

private fun assertRetryRejections(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: FailedRunRetryContext,
) {
    mockMvc
        .perform(retry(context.otherTenantId, context.failedRunId, context.subject, 4))
        .andExpect(status().isNotFound)
    mockMvc
        .perform(retry(context.tenantId, context.failedRunId, context.subject, 3))
        .andExpect(status().isPreconditionFailed)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:stale-case-version"))
    assertThat(runCountForCase(jdbcClient, context.tenantId, context.caseId)).isEqualTo(1)
    assertThat(runStateAndVersion(jdbcClient, context.tenantId, context.failedRunId))
        .isEqualTo("ACTION_FAILED" to 1L)
}

private fun startRetryAndAssert(
    mockMvc: MockMvc,
    context: FailedRunRetryContext,
): UUID {
    val result =
        mockMvc
            .perform(retry(context.tenantId, context.failedRunId, context.subject, 4))
            .andExpect(status().isCreated)
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.failedRunId").value(context.failedRunId.toString()))
            .andExpect(jsonPath("$.failedRunState").value("SUPERSEDED"))
            .andExpect(jsonPath("$.failedRunStateVersion").value(2))
            .andExpect(jsonPath("$.requestedByActorId").value(context.actorId.toString()))
            .andExpect(jsonPath("$.authorityEvidenceId").value(context.authorityEvidenceId.toString()))
            .andExpect(jsonPath("$.retryPolicyRevision").value(RETRY_POLICY_REVISION))
            .andExpect(jsonPath("$.retrySourceAttemptNumber").value(1))
            .andExpect(jsonPath("$.retryMaximumAttempts").value(2))
            .andExpect(jsonPath("$.replacementRun.attemptNumber").value(2))
            .andExpect(jsonPath("$.replacementRun.predecessorRunId").value(context.failedRunId.toString()))
            .andExpect(jsonPath("$.replacementRun.initialState").value("WAITING_FOR_APPROVAL"))
            .andReturn()
    return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
}

private fun assertRetryReplay(
    mockMvc: MockMvc,
    context: FailedRunRetryContext,
    replacementRunId: UUID,
) {
    mockMvc
        .perform(retry(context.tenantId, context.failedRunId, context.subject, 4))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.replacementRun.runId").value(replacementRunId.toString()))
        .andExpect(jsonPath("$.retryPolicyRevision").value(RETRY_POLICY_REVISION))
        .andExpect(jsonPath("$.retrySourceAttemptNumber").value(1))
        .andExpect(jsonPath("$.retryMaximumAttempts").value(2))
    mockMvc
        .perform(retry(context.tenantId, replacementRunId, context.subject, 4))
        .andExpect(status().isConflict)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-retryable"))
        .andExpect(jsonPath("$.state").value("WAITING_FOR_APPROVAL"))
}

private fun runStateAndVersion(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
): Pair<String, Long> =
    jdbcClient
        .sql("SELECT state, version FROM resolution_run_states WHERE tenant_id = :tenantId AND run_id = :runId")
        .param("tenantId", tenantId)
        .param("runId", runId)
        .query { row, _ -> row.getString("state") to row.getLong("version") }
        .single()

private fun runCountForCase(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    caseId: UUID,
): Long =
    jdbcClient
        .sql("SELECT count(*) FROM resolution_runs WHERE tenant_id = :tenantId AND case_id = :caseId")
        .param("tenantId", tenantId)
        .param("caseId", caseId)
        .query(Long::class.java)
        .single()

private fun assertRetryPersistence(
    jdbcClient: JdbcClient,
    context: FailedRunRetryContext,
    replacementRunId: UUID,
) {
    assertThat(runStateAndVersion(jdbcClient, context.tenantId, context.failedRunId))
        .isEqualTo("SUPERSEDED" to 2L)
    assertThat(runStateAndVersion(jdbcClient, context.tenantId, replacementRunId))
        .isEqualTo("WAITING_FOR_APPROVAL" to 0L)
    assertThat(runCountForCase(jdbcClient, context.tenantId, context.caseId)).isEqualTo(2)
    val linkedReplacement =
        jdbcClient
            .sql(
                """
                SELECT replacement_run_id, retry_actor_id, retry_authority_evidence_id
                FROM resolution_run_events
                WHERE tenant_id = :tenantId AND run_id = :failedRunId
                    AND sequence = 2 AND event_type = 'RETRY_STARTED'
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("failedRunId", context.failedRunId)
            .query { row, _ ->
                Triple(
                    row.getObject("replacement_run_id", UUID::class.java),
                    row.getObject("retry_actor_id", UUID::class.java),
                    row.getObject("retry_authority_evidence_id", UUID::class.java),
                )
            }.single()
    assertThat(linkedReplacement)
        .isEqualTo(Triple(replacementRunId, context.actorId, context.authorityEvidenceId))
    assertThatThrownBy {
        jdbcClient
            .sql(
                """
                UPDATE resolution_run_events
                SET replacement_run_id = replacement_run_id
                WHERE tenant_id = :tenantId AND run_id = :failedRunId AND sequence = 2
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("failedRunId", context.failedRunId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
}

private fun retry(
    tenantId: UUID,
    runId: UUID,
    subject: String,
    expectedCaseVersion: Long,
) = post(RUN_RETRIES_PATH, tenantId, runId)
    .with(
        jwt().jwt {
            it.issuer(TRUSTED_ISSUER)
            it.subject(subject)
        },
    ).header("If-Match", "\"$expectedCaseVersion\"")

private fun escalate(
    tenantId: UUID,
    runId: UUID,
    subject: String,
) = post(RUN_ESCALATIONS_PATH, tenantId, runId)
    .with(
        jwt().jwt {
            it.issuer(TRUSTED_ISSUER)
            it.subject(subject)
        },
    )

private fun assertRetryOperationMeaningConstrained(
    jdbcClient: JdbcClient,
    context: FailedRunRetryContext,
) {
    assertThatThrownBy {
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_runs (
                    tenant_id, run_id, case_id, case_stream_version,
                    contract_key, contract_revision, policy_revision,
                    step_id, capability, effective_risk, required_approval,
                    initial_state, attempt_number, predecessor_run_id
                )
                SELECT
                    tenant_id, :replacementRunId, case_id, case_stream_version,
                    contract_key, contract_revision, policy_revision,
                    step_id, 'identity.account.disable', effective_risk, required_approval,
                    initial_state, 2, run_id
                FROM resolution_runs
                WHERE tenant_id = :tenantId AND run_id = :failedRunId
                """.trimIndent(),
            ).param("replacementRunId", UUID.randomUUID())
            .param("tenantId", context.tenantId)
            .param("failedRunId", context.failedRunId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
}

private fun invokeAndAssertConsumption(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: CapabilityInvocationTestContext,
) {
    val consumptionId = context.consumptionId
    mockMvc
        .perform(post(CAPABILITY_INVOCATIONS_PATH, UUID.randomUUID(), consumptionId))
        .andExpect(status().isNotFound)
        .andExpect(
            jsonPath("$.type")
                .value("urn:ergon:problem:capability-authorization-consumption-not-found"),
        )

    val providerReference = "identity-stub/operations/$consumptionId"
    mockMvc
        .perform(post(CAPABILITY_INVOCATIONS_PATH, context.tenantId, consumptionId))
        .andExpect(status().isCreated)
        .andExpect(jsonPath("$.authorizationConsumptionId").value(consumptionId.toString()))
        .andExpect(jsonPath("$.authorizationGrantId").value(context.grantId.toString()))
        .andExpect(jsonPath("$.runId").value(context.runId.toString()))
        .andExpect(jsonPath("$.caseId").value(context.caseId.toString()))
        .andExpect(jsonPath("$.policyRevision").value("ergon.dev/policy/access-restoration/v1"))
        .andExpect(jsonPath("$.stepId").value("unlock-account"))
        .andExpect(jsonPath("$.capability").value("identity.account.unlock"))
        .andExpect(jsonPath("$.connector").value("identity-stub"))
        .andExpect(jsonPath("$.idempotencyKey").value(consumptionId.toString()))
        .andExpect(jsonPath("$.outcome").value("SUCCEEDED"))
        .andExpect(jsonPath("$.providerOperationReference").value(providerReference))
        .andExpect(jsonPath("$.completedAt").exists())
        .andExpect(jsonPath("$.recordedAt").exists())

    mockMvc
        .perform(post(CAPABILITY_INVOCATIONS_PATH, context.tenantId, consumptionId))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.authorizationConsumptionId").value(consumptionId.toString()))
        .andExpect(jsonPath("$.idempotencyKey").value(consumptionId.toString()))
        .andExpect(jsonPath("$.providerOperationReference").value(providerReference))

    assertThatThrownBy {
        jdbcClient
            .sql(
                """
                UPDATE capability_invocation_receipts
                SET outcome = outcome
                WHERE tenant_id = :tenantId AND authorization_consumption_id = :consumptionId
                """.trimIndent(),
            ).param("tenantId", context.tenantId)
            .param("consumptionId", consumptionId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
}

private fun expectCapabilityResultReceiptMissing(
    mockMvc: MockMvc,
    tenantId: UUID,
    runId: UUID,
) {
    mockMvc
        .perform(post(RUN_CAPABILITY_RESULTS_PATH, tenantId, runId))
        .andExpect(status().isConflict)
        .andExpect(
            jsonPath("$.type")
                .value("urn:ergon:problem:resolution-run-capability-receipt-not-found"),
        )
}

private fun recordAndAssertCapabilityResult(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    context: CapabilityResultTestContext,
) {
    mockMvc
        .perform(post(RUN_CAPABILITY_RESULTS_PATH, UUID.randomUUID(), context.runId))
        .andExpect(status().isNotFound)
        .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-found"))
    appendAndReplayCapabilityResult(
        mockMvc,
        jdbcClient,
        context.tenantId,
        context.runId,
        context.consumptionId,
    )
    assertCapabilityResultPersistence(jdbcClient, context.tenantId, context.runId, context.caseId)
}

private fun appendAndReplayCapabilityResult(
    mockMvc: MockMvc,
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
    consumptionId: UUID,
) {
    mockMvc
        .perform(post(RUN_CAPABILITY_RESULTS_PATH, tenantId, runId))
        .andExpect(status().isCreated)
        .andExpect(jsonPath("$.runId").value(runId.toString()))
        .andExpect(jsonPath("$.sequence").value(1))
        .andExpect(jsonPath("$.eventType").value("CAPABILITY_SUCCEEDED"))
        .andExpect(jsonPath("$.fromState").value("WAITING_FOR_APPROVAL"))
        .andExpect(jsonPath("$.toState").value("VERIFYING"))
        .andExpect(jsonPath("$.authorizationConsumptionId").value(consumptionId.toString()))
        .andExpect(jsonPath("$.receiptOutcome").value("SUCCEEDED"))
        .andExpect(jsonPath("$.occurredAt").exists())
        .andExpect(jsonPath("$.recordedAt").exists())
        .andExpect(jsonPath("$.currentState").value("VERIFYING"))
        .andExpect(jsonPath("$.stateVersion").value(1))
    val eventId = runEventId(jdbcClient, tenantId, runId)

    mockMvc
        .perform(post(RUN_CAPABILITY_RESULTS_PATH, tenantId, runId))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.eventId").value(eventId.toString()))
        .andExpect(jsonPath("$.currentState").value("VERIFYING"))
        .andExpect(jsonPath("$.stateVersion").value(1))
}

private fun assertCapabilityResultPersistence(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
    caseId: UUID,
) {
    assertThat(runState(jdbcClient, tenantId, runId)).isEqualTo("VERIFYING:1")
    assertThat(runInitialState(jdbcClient, tenantId, runId)).isEqualTo("WAITING_FOR_APPROVAL")
    assertThat(caseStatus(jdbcClient, tenantId, caseId)).isEqualTo("OPEN")
    val eventId = runEventId(jdbcClient, tenantId, runId)
    assertThatThrownBy {
        jdbcClient
            .sql(
                """
                UPDATE resolution_run_events
                SET event_type = event_type
                WHERE tenant_id = :tenantId AND event_id = :eventId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("eventId", eventId)
            .update()
    }.isInstanceOf(DataAccessException::class.java)
}

private fun runState(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
): String =
    jdbcClient
        .sql(
            """
            SELECT state || ':' || version
            FROM resolution_run_states
            WHERE tenant_id = :tenantId AND run_id = :runId
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("runId", runId)
        .query(String::class.java)
        .single()

private fun runInitialState(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
): String =
    jdbcClient
        .sql(
            """
            SELECT initial_state
            FROM resolution_runs
            WHERE tenant_id = :tenantId AND run_id = :runId
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("runId", runId)
        .query(String::class.java)
        .single()

private fun caseStatus(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    caseId: UUID,
): String =
    jdbcClient
        .sql(
            """
            SELECT status
            FROM cases
            WHERE tenant_id = :tenantId AND case_id = :caseId
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("caseId", caseId)
        .query(String::class.java)
        .single()

private fun caseStreamVersion(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    caseId: UUID,
): Long =
    jdbcClient
        .sql(
            """
            SELECT stream_version
            FROM cases
            WHERE tenant_id = :tenantId AND case_id = :caseId
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("caseId", caseId)
        .query(Long::class.java)
        .single()

private fun runEventId(
    jdbcClient: JdbcClient,
    tenantId: UUID,
    runId: UUID,
): UUID =
    jdbcClient
        .sql(
            """
            SELECT event_id
            FROM resolution_run_events
            WHERE tenant_id = :tenantId AND run_id = :runId
            """.trimIndent(),
        ).param("tenantId", tenantId)
        .param("runId", runId)
        .query(UUID::class.java)
        .single()
