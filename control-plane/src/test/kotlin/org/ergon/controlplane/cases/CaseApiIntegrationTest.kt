package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
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

        assertTimelinePreservesEventTimes(tenantId, caseId)
    }

    private fun assertTimelinePreservesEventTimes(
        tenantId: UUID,
        caseId: UUID,
    ) {
        val eventTimes =
            jdbcClient
                .sql(
                    """
                    SELECT
                        e.occurred_at,
                        e.recorded_at,
                        t.occurred_at AS timeline_occurred_at,
                        t.recorded_at AS timeline_recorded_at
                    FROM case_events e
                    INNER JOIN case_timeline_entries t
                        ON t.tenant_id = e.tenant_id
                        AND t.case_id = e.case_id
                        AND t.stream_version = e.stream_version
                        AND t.event_id = e.event_id
                    WHERE e.tenant_id = :tenantId AND e.case_id = :caseId
                    ORDER BY e.stream_version
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query { resultSet, _ ->
                    EventTimelineTimes(
                        occurredAt = resultSet.getObject("occurred_at", OffsetDateTime::class.java),
                        recordedAt = resultSet.getObject("recorded_at", OffsetDateTime::class.java),
                        timelineOccurredAt =
                            resultSet.getObject("timeline_occurred_at", OffsetDateTime::class.java),
                        timelineRecordedAt =
                            resultSet.getObject("timeline_recorded_at", OffsetDateTime::class.java),
                    )
                }.list()
        assertThat(eventTimes).hasSize(2)
        assertThat(eventTimes).allSatisfy { times ->
            assertThat(times.timelineOccurredAt).isEqualTo(times.occurredAt)
            assertThat(times.timelineRecordedAt).isEqualTo(times.recordedAt)
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
    fun `binds and returns typed account access state with its evidence`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)
        recordConnectorObservation(tenantId, caseId, "\"1\"")
        val observationId = connectorObservationId(tenantId, caseId)

        bindAccountAccessState(tenantId, caseId, observationId, "\"2\"")

        mockMvc
            .perform(
                post(INTERNAL_ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId)
                    .header("If-Match", "\"3\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(accountAccessFact(observationId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-case-command"))

        mockMvc
            .perform(get(ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.streamVersion").value(3))
            .andExpect(jsonPath("$.facts.length()").value(1))
            .andExpect(jsonPath("$.facts[0].streamVersion").value(3))
            .andExpect(jsonPath("$.facts[0].observationId").value(observationId.toString()))
            .andExpect(jsonPath("$.facts[0].accountReference").value("accounts/customer-42"))
            .andExpect(jsonPath("$.facts[0].state").value("LOCKED"))
            .andExpect(jsonPath("$.facts[0].boundAt").exists())
            .andExpect(jsonPath("$.facts[0].recordedAt").exists())

        assertFactPreservesEventTimes(tenantId, caseId)
    }

    @Test
    fun `returns no facts before binding and rejects unsupported evidence`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)
        val requesterObservationId = observationIdAtVersion(tenantId, caseId, 1)

        mockMvc
            .perform(get(ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.streamVersion").value(1))
            .andExpect(jsonPath("$.facts.length()").value(0))

        mockMvc
            .perform(
                post(INTERNAL_ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId)
                    .header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(accountAccessFact(requesterObservationId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-case-command"))
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

        recordConnectorObservation(ownerTenantId, caseId, "\"1\"")
        val observationId = connectorObservationId(ownerTenantId, caseId)

        mockMvc
            .perform(get(ACCOUNT_ACCESS_FACTS_PATH, otherTenantId, caseId))
            .andExpect(status().isNotFound)

        mockMvc
            .perform(
                post(INTERNAL_ACCOUNT_ACCESS_FACTS_PATH, otherTenantId, caseId)
                    .header("If-Match", "\"2\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(accountAccessFact(observationId)),
            ).andExpect(status().isNotFound)

        val ownerVersion =
            jdbcClient
                .sql("SELECT stream_version FROM cases WHERE tenant_id = :tenantId AND case_id = :caseId")
                .param("tenantId", ownerTenantId)
                .param("caseId", caseId)
                .query(Long::class.java)
                .single()
        assertThat(ownerVersion).isEqualTo(2)
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
            ).andExpect(content().string(containsString(ACCOUNT_ACCESS_FACTS_PATH)))
            .andExpect(content().string(containsString(INTERNAL_ACCOUNT_ACCESS_FACTS_PATH)))
    }

    @Test
    fun `rejects mutation of immutable case events`() {
        val tenantId = UUID.randomUUID()
        val caseId = openCase(tenantId)

        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    UPDATE case_events
                    SET event_type = event_type
                    WHERE tenant_id = :tenantId AND case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .update()
        }.isInstanceOf(DataAccessException::class.java)

        val eventCount =
            jdbcClient
                .sql(
                    """
                    SELECT count(*)
                    FROM case_events
                    WHERE tenant_id = :tenantId AND case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query(Long::class.java)
                .single()
        assertThat(eventCount).isEqualTo(1)
    }

    @Test
    fun `migrates a populated case timeline from the previous schema`() {
        val schema = "upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        flyway(schema)
            .target(PREVIOUS_SCHEMA_VERSION)
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO case_events (
                        event_id, tenant_id, case_id, stream_version, event_type,
                        schema_version, payload, occurred_at
                    ) VALUES (
                        '$UPGRADE_EVENT_ID', '$UPGRADE_TENANT_ID', '$UPGRADE_CASE_ID', 1,
                        'CaseOpened', 1, '{}'::JSONB, '2026-09-11T10:15:30Z'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO cases (
                        tenant_id, case_id, goal, status, stream_version, opened_at, updated_at
                    ) VALUES (
                        '$UPGRADE_TENANT_ID', '$UPGRADE_CASE_ID', 'Restore workspace access',
                        'OPEN', 1, '2026-09-11T10:15:30Z', '2026-09-11T10:15:30Z'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO case_timeline_entries (
                        tenant_id, case_id, stream_version, event_id, entry_type, summary,
                        observation_id, observation_origin_type, observation_provider,
                        observation_reference, observation_content, occurred_at, recorded_at
                    ) VALUES (
                        '$UPGRADE_TENANT_ID', '$UPGRADE_CASE_ID', 1, '$UPGRADE_EVENT_ID',
                        'CASE_OPENED', 'Case opened from requester observation',
                        '$UPGRADE_OBSERVATION_ID', 'REQUESTER', 'api', NULL,
                        'I cannot sign in.', '2026-09-11T10:15:30Z', '2026-09-11T10:15:30Z'
                    )
                    """.trimIndent(),
                )
            }
        }

        flyway(schema).load().migrate()
        assertPreviousCaseSurvivedUpgrade(schema)
    }

    private fun assertPreviousCaseSurvivedUpgrade(schema: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM cases").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getInt(1)).isEqualTo(1)
                }
                statement.executeQuery("SELECT to_regclass('case_account_access_facts')").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString(1)).isEqualTo("case_account_access_facts")
                }
            }
        }
    }

    private fun flyway(schema: String) =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .defaultSchema(schema)
            .schemas(schema)
            .table("ergon_flyway_schema_history")
            .locations("classpath:db/migration")

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

    private fun bindAccountAccessState(
        tenantId: UUID,
        caseId: UUID,
        observationId: UUID,
        version: String,
    ) {
        mockMvc
            .perform(
                post(INTERNAL_ACCOUNT_ACCESS_FACTS_PATH, tenantId, caseId)
                    .header("If-Match", version)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(accountAccessFact(observationId)),
            ).andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.streamVersion").value(3))
    }

    private fun connectorObservationId(
        tenantId: UUID,
        caseId: UUID,
    ): UUID = observationIdAtVersion(tenantId, caseId, 2)

    private fun observationIdAtVersion(
        tenantId: UUID,
        caseId: UUID,
        streamVersion: Long,
    ): UUID =
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
            .param("streamVersion", streamVersion)
            .query(UUID::class.java)
            .single()

    private fun assertFactPreservesEventTimes(
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
                        f.bound_at,
                        f.recorded_at AS fact_recorded_at
                    FROM case_events e
                    INNER JOIN case_account_access_facts f ON f.event_id = e.event_id
                    WHERE e.tenant_id = :tenantId AND e.case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("caseId", caseId)
                .query { resultSet, _ ->
                    EventFactTimes(
                        occurredAt = resultSet.getObject("occurred_at", OffsetDateTime::class.java),
                        recordedAt = resultSet.getObject("recorded_at", OffsetDateTime::class.java),
                        boundAt = resultSet.getObject("bound_at", OffsetDateTime::class.java),
                        factRecordedAt = resultSet.getObject("fact_recorded_at", OffsetDateTime::class.java),
                    )
                }.single()
        assertThat(times.boundAt).isEqualTo(times.occurredAt)
        assertThat(times.factRecordedAt).isEqualTo(times.recordedAt)
    }

    companion object {
        private const val OPEN_CASE =
            """{"goal":"Restore workspace access","initialObservation":"I cannot sign in."}"""
        private const val CONNECTOR_OBSERVATION =
            """{"connector":"identity-stub","reference":"accounts/customer-42","content":"status=LOCKED"}"""
        private const val ACCOUNT_ACCESS_FACTS_PATH =
            "/api/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states"
        private const val INTERNAL_ACCOUNT_ACCESS_FACTS_PATH =
            "/internal/v1/tenants/{tenantId}/cases/{caseId}/facts/account-access-states"
        private const val PREVIOUS_SCHEMA_VERSION = "20260911210000"
        private const val UPGRADE_EVENT_ID = "55555555-5555-5555-5555-555555555555"
        private const val UPGRADE_TENANT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        private const val UPGRADE_CASE_ID = "66666666-6666-6666-6666-666666666666"
        private const val UPGRADE_OBSERVATION_ID = "77777777-7777-7777-7777-777777777777"

        private fun accountAccessFact(observationId: UUID) = """{"observationId":"$observationId","state":"LOCKED"}"""

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

private data class EventTimelineTimes(
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val timelineOccurredAt: OffsetDateTime,
    val timelineRecordedAt: OffsetDateTime,
)

private data class EventFactTimes(
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val boundAt: OffsetDateTime,
    val factRecordedAt: OffsetDateTime,
)
