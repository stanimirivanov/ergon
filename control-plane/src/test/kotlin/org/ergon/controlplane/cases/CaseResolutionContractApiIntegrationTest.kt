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
        private const val CONTRACT_REVISIONS_PATH = "/internal/v1/tenants/{tenantId}/resolution-contracts"
        private const val CONTRACT_PIN = """{"key":"restore-workspace-access","revision":1}"""
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
