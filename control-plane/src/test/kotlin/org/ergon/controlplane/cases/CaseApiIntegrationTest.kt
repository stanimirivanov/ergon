package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
class CaseApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `opens case records connector observation and returns ordered timeline`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)

        mockMvc
            .perform(
                post("/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations", tenantId, caseId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.streamVersion").value(2))

        mockMvc
            .perform(get("/api/v1/tenants/{tenantId}/cases/{caseId}/timeline", tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.goal").value("Restore workspace access"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.streamVersion").value(2))
            .andExpect(jsonPath("$.entries.length()").value(2))
            .andExpect(jsonPath("$.entries[0].streamVersion").value(1))
            .andExpect(jsonPath("$.entries[0].eventType").value("CASE_OPENED"))
            .andExpect(jsonPath("$.entries[0].observation.originType").value("REQUESTER"))
            .andExpect(jsonPath("$.entries[0].observation.content").value("I cannot sign in."))
            .andExpect(jsonPath("$.entries[1].streamVersion").value(2))
            .andExpect(jsonPath("$.entries[1].eventType").value("OBSERVATION_RECORDED"))
            .andExpect(jsonPath("$.entries[1].observation.originType").value("CONNECTOR"))
            .andExpect(jsonPath("$.entries[1].observation.provider").value("identity-stub"))
            .andExpect(jsonPath("$.entries[1].observation.reference").value("accounts/customer-42"))
            .andExpect(jsonPath("$.entries[1].observation.content").value("status=LOCKED"))

        val eventTimes =
            jdbcClient
                .sql(
                    """
                    select occurred_at, recorded_at
                    from case_events
                    where tenant_id = :tenantId and case_id = :caseId
                    order by stream_version
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query { resultSet, _ ->
                    resultSet.getObject("occurred_at", OffsetDateTime::class.java) to
                        resultSet.getObject("recorded_at", OffsetDateTime::class.java)
                }.list()
        assertThat(eventTimes).hasSize(2)
        assertThat(eventTimes).allSatisfy { (occurredAt, recordedAt) ->
            assertThat(recordedAt).isAfterOrEqualTo(occurredAt)
        }
    }

    @Test
    fun `rejects stale and missing connector preconditions`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)
        recordConnectorObservation(tenantId, caseId, "\"1\"")

        mockMvc
            .perform(
                post("/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations", tenantId, caseId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isPreconditionFailed)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:stale-case-version"))
            .andExpect(jsonPath("$.expectedVersion").value(1))
            .andExpect(jsonPath("$.actualVersion").value(2))

        mockMvc
            .perform(
                post("/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations", tenantId, caseId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isPreconditionRequired)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:missing-precondition"))
    }

    @Test
    fun `does not reveal or mutate a case across tenants`() {
        val ownerTenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val caseId = openCase(ownerTenantId)

        mockMvc
            .perform(get("/api/v1/tenants/{tenantId}/cases/{caseId}/timeline", otherTenantId, caseId))
            .andExpect(status().isNotFound)

        mockMvc
            .perform(
                post(
                    "/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations",
                    otherTenantId,
                    caseId,
                ).header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isNotFound)

        val ownerVersion =
            jdbcClient
                .sql("select stream_version from cases where tenant_id = :tenantId and case_id = :caseId")
                .param("tenantId", ownerTenantId)
                .param("caseId", caseId)
                .query(Long::class.java)
                .single()
        assertThat(ownerVersion).isEqualTo(1)
    }

    @Test
    fun `publishes the case API contract`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/tenants/{tenantId}/cases'].post").exists())
            .andExpect(jsonPath("$.paths['/api/v1/tenants/{tenantId}/cases/{caseId}/timeline'].get").exists())
            .andExpect(
                content().string(
                    containsString(
                        "/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations",
                    ),
                ),
            )
    }

    private fun openCase(tenantId: UUID): UUID {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tenants/{tenantId}/cases", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_CASE),
                ).andExpect(status().isCreated)
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.streamVersion").value(1))
                .andReturn()
        return UUID.fromString(
            response.response
                .getHeader("Location")!!
                .removeSuffix("/timeline")
                .substringAfterLast('/'),
        )
    }

    private fun recordConnectorObservation(
        tenantId: UUID,
        caseId: UUID,
        version: String,
    ) {
        mockMvc
            .perform(
                post("/internal/v1/tenants/{tenantId}/cases/{caseId}/connector-observations", tenantId, caseId)
                    .header("If-Match", version)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CONNECTOR_OBSERVATION),
            ).andExpect(status().isOk)
    }

    companion object {
        private const val OPEN_CASE =
            """{"goal":"Restore workspace access","initialObservation":"I cannot sign in."}"""
        private const val CONNECTOR_OBSERVATION =
            """{"connector":"identity-stub","reference":"accounts/customer-42","content":"status=LOCKED"}"""

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
