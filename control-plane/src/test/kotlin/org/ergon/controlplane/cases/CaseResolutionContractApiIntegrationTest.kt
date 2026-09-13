package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
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
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CaseResolutionContractApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `pins a published contract revision without adding source timeline evidence`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)
        publishContract(tenantId)

        pinContract(tenantId, caseId, "\"1\"")

        mockMvc
            .perform(get(CASE_TIMELINE_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.streamVersion").value(2))
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.resolutionContract.key").value("restore-workspace-access"))
            .andExpect(jsonPath("$.resolutionContract.revision").value(1))
            .andExpect(jsonPath("$.resolutionContract.streamVersion").value(2))
            .andExpect(jsonPath("$.resolutionContract.pinnedAt").exists())
            .andExpect(jsonPath("$.resolutionContract.recordedAt").exists())

        assertContractPinPreservesEventTimes(tenantId, caseId)

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE case_resolution_contract_pins
                    SET contract_revision = contract_revision
                    WHERE tenant_id = :tenantId AND case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `rejects absent cross tenant and replacement contract pins`() {
        val ownerTenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val caseId = openCase(ownerTenantId)
        publishContract(otherTenantId)

        expectNotFound(ownerTenantId, caseId, "urn:ergon:problem:contract-revision-not-found")
        expectNotFound(otherTenantId, caseId, "urn:ergon:problem:case-not-found")

        publishContract(ownerTenantId)
        mockMvc
            .perform(
                post(CASE_RESOLUTION_CONTRACT_PATH, ownerTenantId, caseId)
                    .header("If-Match", "\"2\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONTRACT_PIN),
            ).andExpect(status().isPreconditionFailed)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:stale-case-version"))

        pinContract(ownerTenantId, caseId, "\"1\"")

        mockMvc
            .perform(
                post(CASE_RESOLUTION_CONTRACT_PATH, ownerTenantId, caseId)
                    .header("If-Match", "\"2\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONTRACT_PIN),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-case-command"))

        val pins =
            jdbcClient
                .sql(
                    """
                    SELECT count(*)
                    FROM case_resolution_contract_pins
                    WHERE tenant_id = :tenantId AND case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", ownerTenantId)
                .param("caseId", caseId)
                .query(Long::class.java)
                .single()
        assertThat(pins).isEqualTo(1)
    }

    @Test
    fun `readiness progresses from missing contract and evidence to ready`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)

        expectReadiness(tenantId, caseId, "WAITING_FOR_CONTRACT", 1)

        publishContract(tenantId)
        pinContract(tenantId, caseId, "\"1\"")
        mockMvc
            .perform(get(CASE_RESOLUTION_READINESS_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("WAITING_FOR_EVIDENCE"))
            .andExpect(jsonPath("$.caseStreamVersion").value(2))
            .andExpect(jsonPath("$.contract.key").value("restore-workspace-access"))
            .andExpect(jsonPath("$.contract.revision").value(1))
            .andExpect(jsonPath("$.missingEvidence[0]").value("account.access.state"))
            .andExpect(jsonPath("$.applicability").doesNotExist())

        recordAndBindAccountState(tenantId, caseId, expectedVersion = 2, state = "LOCKED")
        mockMvc
            .perform(get(CASE_RESOLUTION_READINESS_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.caseStreamVersion").value(4))
            .andExpect(jsonPath("$.missingEvidence.length()").value(0))
            .andExpect(jsonPath("$.applicability.fact").value("account.access.state"))
            .andExpect(jsonPath("$.applicability.expectedValue").value("LOCKED"))
            .andExpect(jsonPath("$.applicability.actualValue").value("LOCKED"))
    }

    @Test
    fun `readiness uses latest evidence and preserves tenant isolation`() {
        val ownerTenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val caseId = openCase(ownerTenantId)
        publishContract(ownerTenantId)
        pinContract(ownerTenantId, caseId, "\"1\"")
        recordAndBindAccountState(ownerTenantId, caseId, expectedVersion = 2, state = "LOCKED")
        recordAndBindAccountState(ownerTenantId, caseId, expectedVersion = 4, state = "ACTIVE")

        mockMvc
            .perform(get(CASE_RESOLUTION_READINESS_PATH, ownerTenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NOT_APPLICABLE"))
            .andExpect(jsonPath("$.caseStreamVersion").value(6))
            .andExpect(jsonPath("$.applicability.expectedValue").value("LOCKED"))
            .andExpect(jsonPath("$.applicability.actualValue").value("ACTIVE"))

        mockMvc
            .perform(get(CASE_RESOLUTION_READINESS_PATH, otherTenantId, caseId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:case-not-found"))
    }

    @Test
    fun `plan exposes versioned policy requirements only when evidence is ready`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)

        mockMvc
            .perform(get(CASE_RESOLUTION_PLAN_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.readiness.status").value("WAITING_FOR_CONTRACT"))
            .andExpect(jsonPath("$.readiness.caseStreamVersion").value(1))
            .andExpect(jsonPath("$.policyRevision").value("ergon.dev/policy/access-restoration/v1"))
            .andExpect(jsonPath("$.nextStep").doesNotExist())

        publishContract(tenantId)
        pinContract(tenantId, caseId, "\"1\"")
        recordAndBindAccountState(tenantId, caseId, expectedVersion = 2, state = "LOCKED")

        mockMvc
            .perform(get(CASE_RESOLUTION_PLAN_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.readiness.status").value("READY"))
            .andExpect(jsonPath("$.readiness.caseStreamVersion").value(4))
            .andExpect(jsonPath("$.nextStep.id").value("unlock-account"))
            .andExpect(jsonPath("$.nextStep.capability").value("identity.account.unlock"))
            .andExpect(jsonPath("$.nextStep.declaredRisk").value("HIGH"))
            .andExpect(jsonPath("$.nextStep.declaredApproval").value("REQUESTER"))
            .andExpect(jsonPath("$.nextStep.decision").value("HUMAN_APPROVAL_REQUIRED"))
            .andExpect(jsonPath("$.nextStep.effectiveRisk").value("HIGH"))
            .andExpect(jsonPath("$.nextStep.requiredApproval").value("REQUESTER"))
            .andExpect(jsonPath("$.nextStep.denialReason").doesNotExist())
    }

    @Test
    fun `starts and retrieves an immutable run from an exact ready case version`() {
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)
        publishContract(tenantId)
        pinContract(tenantId, caseId, "\"1\"")
        recordAndBindAccountState(tenantId, caseId, expectedVersion = 2, state = "LOCKED")

        expectCrossTenantRunStartNotFound(otherTenantId, caseId)

        val response =
            mockMvc
                .perform(
                    post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId)
                        .header("If-Match", "\"4\""),
                ).andExpect(status().isCreated)
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.caseStreamVersion").value(4))
                .andExpect(jsonPath("$.contractKey").value("restore-workspace-access"))
                .andExpect(jsonPath("$.contractRevision").value(1))
                .andExpect(jsonPath("$.policyRevision").value("ergon.dev/policy/access-restoration/v1"))
                .andExpect(jsonPath("$.stepId").value("unlock-account"))
                .andExpect(jsonPath("$.capability").value("identity.account.unlock"))
                .andExpect(jsonPath("$.effectiveRisk").value("HIGH"))
                .andExpect(jsonPath("$.requiredApproval").value("REQUESTER"))
                .andExpect(jsonPath("$.initialState").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.recordedAt").exists())
                .andReturn()
        val location = requireNotNull(response.response.getHeader("Location"))
        val runId = UUID.fromString(location.substringAfterLast('/'))

        mockMvc
            .perform(get(RESOLUTION_RUN_PATH, tenantId, runId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andExpect(jsonPath("$.caseStreamVersion").value(4))
            .andExpect(jsonPath("$.initialState").value("WAITING_FOR_APPROVAL"))

        mockMvc
            .perform(get(RESOLUTION_RUN_PATH, otherTenantId, runId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-found"))

        mockMvc
            .perform(
                post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId)
                    .header("If-Match", "\"4\""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-already-exists"))

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_runs
                    SET initial_state = initial_state
                    WHERE tenant_id = :tenantId AND run_id = :runId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("runId", runId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `rejects run start when the case is not ready or the evidence version is stale`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)

        mockMvc
            .perform(
                post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId)
                    .header("If-Match", "\"1\""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-ready"))
            .andExpect(jsonPath("$.readiness").value("WAITING_FOR_CONTRACT"))

        publishContract(tenantId)
        pinContract(tenantId, caseId, "\"1\"")
        recordAndBindAccountState(tenantId, caseId, expectedVersion = 2, state = "LOCKED")

        mockMvc
            .perform(
                post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId)
                    .header("If-Match", "\"3\""),
            ).andExpect(status().isPreconditionFailed)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:stale-case-version"))
            .andExpect(jsonPath("$.expectedVersion").value(3))
            .andExpect(jsonPath("$.actualVersion").value(4))

        val runCount =
            jdbcClient
                .sql(
                    """
                    SELECT count(*)
                    FROM resolution_runs
                    WHERE tenant_id = :tenantId AND case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(Long::class.java)
                .single()
        assertThat(runCount).isZero()
    }

    @Test
    fun `creates and retrieves one active immutable approval request within its tenant`() {
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)

        mockMvc
            .perform(post(APPROVAL_REQUESTS_PATH, otherTenantId, runId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:resolution-run-not-found"))

        val requestId = createApprovalRequest(tenantId, runId)

        mockMvc
            .perform(get(APPROVAL_REQUEST_PATH, tenantId, requestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.approvalRequestId").value(requestId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))

        mockMvc
            .perform(get(APPROVAL_REQUEST_PATH, otherTenantId, requestId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-request-not-found"))

        mockMvc
            .perform(post(APPROVAL_REQUESTS_PATH, tenantId, runId))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:active-approval-request-exists"))
            .andExpect(jsonPath("$.approvalRequestId").value(requestId.toString()))
            .andExpect(jsonPath("$.expiresAt").exists())

        assertApprovalRequestLifetimeAndImmutability(tenantId, requestId)
    }

    @Test
    fun `retains an expired request and creates a replacement`() {
        val tenantId = UUID.randomUUID()
        val runId = prepareResolutionRun(tenantId)
        val expiredRequestId = insertExpiredApprovalRequest(tenantId, runId)

        mockMvc
            .perform(get(APPROVAL_REQUEST_PATH, tenantId, expiredRequestId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("EXPIRED"))

        createApprovalRequest(tenantId, runId)

        val count =
            jdbcClient
                .sql(
                    """
                    SELECT count(*)
                    FROM resolution_approval_requests
                    WHERE tenant_id = :tenantId AND run_id = :runId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("runId", runId)
                .query(Long::class.java)
                .single()
        assertThat(count).isEqualTo(2)
    }

    private fun expectReadiness(
        tenantId: UUID,
        caseId: UUID,
        readinessStatus: String,
        caseStreamVersion: Long,
    ) {
        mockMvc
            .perform(get(CASE_RESOLUTION_READINESS_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.caseStreamVersion").value(caseStreamVersion))
            .andExpect(jsonPath("$.status").value(readinessStatus))
            .andExpect(jsonPath("$.contract").doesNotExist())
            .andExpect(jsonPath("$.missingEvidence.length()").value(0))
            .andExpect(jsonPath("$.applicability").doesNotExist())
    }

    private fun prepareResolutionRun(tenantId: UUID): UUID {
        val caseId = openCase(tenantId)
        publishContract(tenantId)
        pinContract(tenantId, caseId, "\"1\"")
        recordAndBindAccountState(tenantId, caseId, expectedVersion = 2, state = "LOCKED")
        val response =
            mockMvc
                .perform(
                    post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId)
                        .header("If-Match", "\"4\""),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
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
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.stepId").value("unlock-account"))
                .andExpect(jsonPath("$.requiredAuthority").value("REQUESTER"))
                .andExpect(jsonPath("$.requestedAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.recordedAt").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
        return UUID.fromString(requireNotNull(response.response.getHeader("Location")).substringAfterLast('/'))
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

    private fun assertApprovalRequestLifetimeAndImmutability(
        tenantId: UUID,
        requestId: UUID,
    ) {
        val lifetimeSeconds =
            jdbcClient
                .sql(
                    """
                    SELECT EXTRACT(EPOCH FROM expires_at - requested_at)::BIGINT
                    FROM resolution_approval_requests
                    WHERE tenant_id = :tenantId AND approval_request_id = :requestId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("requestId", requestId)
                .query(Long::class.java)
                .single()
        assertThat(lifetimeSeconds).isEqualTo(900)

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_approval_requests
                    SET expires_at = expires_at
                    WHERE tenant_id = :tenantId AND approval_request_id = :requestId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("requestId", requestId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun expectCrossTenantRunStartNotFound(
        tenantId: UUID,
        caseId: UUID,
    ) {
        mockMvc
            .perform(
                post(CASE_RESOLUTION_RUNS_PATH, tenantId, caseId)
                    .header("If-Match", "\"4\""),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:case-not-found"))
    }

    private fun expectNotFound(
        tenantId: UUID,
        caseId: UUID,
        problemType: String,
    ) {
        mockMvc
            .perform(
                post(CASE_RESOLUTION_CONTRACT_PATH, tenantId, caseId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONTRACT_PIN),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value(problemType))
    }

    private fun openCase(tenantId: UUID): UUID {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tenants/{tenantId}/cases", tenantId)
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
        version: String,
    ) {
        mockMvc
            .perform(
                post(CASE_RESOLUTION_CONTRACT_PATH, tenantId, caseId)
                    .header("If-Match", version)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONTRACT_PIN),
            ).andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.streamVersion").value(2))
    }

    private fun recordAndBindAccountState(
        tenantId: UUID,
        caseId: UUID,
        expectedVersion: Long,
        state: String,
    ) {
        val observationVersion = expectedVersion + 1
        mockMvc
            .perform(
                post(CONNECTOR_OBSERVATIONS_PATH, tenantId, caseId)
                    .header("If-Match", "\"$expectedVersion\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.streamVersion").value(observationVersion))
        val observationId =
            jdbcClient
                .sql(
                    """
                    SELECT observation_id
                    FROM case_timeline_entries
                    WHERE tenant_id = :tenantId
                        AND case_id = :caseId
                        AND stream_version = :streamVersion
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .param("streamVersion", observationVersion)
                .query(UUID::class.java)
                .single()
        mockMvc
            .perform(
                post(ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId)
                    .header("If-Match", "\"$observationVersion\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"observationId":"$observationId","state":"$state"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.streamVersion").value(observationVersion + 1))
    }

    private fun assertContractPinPreservesEventTimes(
        tenantId: UUID,
        caseId: UUID,
    ) {
        val times =
            jdbcClient
                .sql(
                    """
                    SELECT
                        e.occurred_at,
                        e.recorded_at,
                        p.pinned_at,
                        p.recorded_at AS pin_recorded_at
                    FROM case_events e
                    INNER JOIN case_resolution_contract_pins p
                        ON p.tenant_id = e.tenant_id
                        AND p.case_id = e.case_id
                        AND p.stream_version = e.stream_version
                        AND p.event_id = e.event_id
                    WHERE e.tenant_id = :tenantId AND e.case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query { resultSet, _ ->
                    EventContractPinTimes(
                        occurredAt = resultSet.getObject("occurred_at", OffsetDateTime::class.java),
                        recordedAt = resultSet.getObject("recorded_at", OffsetDateTime::class.java),
                        pinnedAt = resultSet.getObject("pinned_at", OffsetDateTime::class.java),
                        pinRecordedAt = resultSet.getObject("pin_recorded_at", OffsetDateTime::class.java),
                    )
                }.single()
        assertThat(times.pinnedAt).isEqualTo(times.occurredAt)
        assertThat(times.pinRecordedAt).isEqualTo(times.recordedAt)
    }

    companion object {
        private const val OPEN_CASE =
            """{"goal":"Restore workspace access","initialObservation":"I cannot sign in."}"""
        private const val CASE_TIMELINE_PATH = "/api/v1/tenants/{tenantId}/cases/{caseId}/timeline"
        private const val CASE_RESOLUTION_CONTRACT_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-contract"
        private const val CASE_RESOLUTION_READINESS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-readiness"
        private const val CASE_RESOLUTION_PLAN_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-plan"
        private const val CASE_RESOLUTION_RUNS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-runs"
        private const val RESOLUTION_RUN_PATH =
            "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}"
        private const val APPROVAL_REQUESTS_PATH =
            "/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/approval-requests"
        private const val APPROVAL_REQUEST_PATH =
            "/internal/v1/tenants/{tenantId}/approval-requests/{requestId}"
        private const val CONNECTOR_OBSERVATIONS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations"
        private const val ACCOUNT_ACCESS_FACTS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states"
        private const val CONTRACT_REVISIONS_PATH = "/internal/v1/tenants/{tenantId}/resolution-contracts"
        private const val CONTRACT_PIN = """{"key":"restore-workspace-access","revision":1}"""
        private const val CONNECTOR_OBSERVATION =
            """{"connector":"identity-stub","reference":"accounts/customer-42","content":"account state observed"}"""
        private val VALID_CONTRACT =
            requireNotNull(
                CaseResolutionContractApiIntegrationTest::class.java
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

private data class EventContractPinTimes(
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val pinnedAt: OffsetDateTime,
    val pinRecordedAt: OffsetDateTime,
)
