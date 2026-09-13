package org.ergon.controlplane.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedHumanAuthorityClockConfiguration::class)
class HumanAuthorityApiIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcClient: JdbcClient,
) {
    @Test
    fun `registers and retrieves an immutable tenant-scoped human identity`() {
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)

        mockMvc
            .perform(get(ACTOR_PATH, tenantId, actorId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actorId").value(actorId.toString()))
            .andExpect(jsonPath("$.identityProvider").value("workforce-sso"))
            .andExpect(jsonPath("$.subject").value("employee-42"))
            .andExpect(jsonPath("$.registeredAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())

        mockMvc
            .perform(get(ACTOR_PATH, otherTenantId, actorId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-found"))

        mockMvc
            .perform(
                post(ACTORS_PATH, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ACTOR_REQUEST),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-identity-exists"))
            .andExpect(jsonPath("$.actorId").value(actorId.toString()))

        assertImmutable("human_actors", "actor_id", actorId)
    }

    @Test
    fun `records case-scoped requester and tenant-scoped resolver evidence`() {
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val caseId = openCase(tenantId)

        val requesterEvidenceId = attest(tenantId, actorId, "REQUESTER", caseId)
        val resolverEvidenceId = attest(tenantId, actorId, "RESOLVER", null)

        mockMvc
            .perform(get(EVIDENCE_PATH, tenantId, requesterEvidenceId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.actorId").value(actorId.toString()))
            .andExpect(jsonPath("$.authority").value("REQUESTER"))
            .andExpect(jsonPath("$.caseId").value(caseId.toString()))
            .andExpect(jsonPath("$.sourceProvider").value("workforce-sso"))
            .andExpect(jsonPath("$.sourceReference").value("claims/authority-42"))
            .andExpect(jsonPath("$.attestedAt").exists())
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.recordedAt").exists())
            .andExpect(jsonPath("$.status").value("CURRENT"))

        mockMvc
            .perform(get(EVIDENCE_PATH, tenantId, resolverEvidenceId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authority").value("RESOLVER"))
            .andExpect(jsonPath("$.caseId").doesNotExist())

        mockMvc
            .perform(get(EVIDENCE_PATH, otherTenantId, requesterEvidenceId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:approval-authority-evidence-not-found"))

        assertImmutable("approval_authority_evidence", "evidence_id", requesterEvidenceId)
    }

    @Test
    fun `rejects invalid authority scope stale identity and excessive lifetime`() {
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val actorId = registerActor(tenantId)
        val caseId = openCase(tenantId)

        expectInvalidAttestation(tenantId, actorId, "REQUESTER", null)
        expectInvalidAttestation(tenantId, actorId, "RESOLVER", caseId)
        assertDatabaseRejectsInvalidScope(tenantId, actorId, caseId)

        mockMvc
            .perform(
                post(ACTOR_EVIDENCE_PATH, otherTenantId, actorId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(attestationRequest("RESOLVER", null, TEST_NOW.plus(1, ChronoUnit.HOURS))),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:human-actor-not-found"))

        val foreignCaseId = openCase(otherTenantId)
        mockMvc
            .perform(
                post(ACTOR_EVIDENCE_PATH, tenantId, actorId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(attestationRequest("REQUESTER", foreignCaseId, TEST_NOW.plus(1, ChronoUnit.HOURS))),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:case-not-found"))

        mockMvc
            .perform(
                post(ACTOR_EVIDENCE_PATH, tenantId, actorId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(attestationRequest("RESOLVER", null, TEST_NOW.plus(25, ChronoUnit.HOURS))),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-authority-request"))

        val evidenceCount =
            jdbcClient
                .sql("SELECT count(*) FROM approval_authority_evidence WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long::class.java)
                .single()
        assertThat(evidenceCount).isZero()
    }

    private fun registerActor(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(
                    post(ACTORS_PATH, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ACTOR_REQUEST),
                ).andExpect(status().isCreated)
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.identityProvider").value("workforce-sso"))
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun openCase(tenantId: UUID): UUID {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/tenants/{tenantId}/cases", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_CASE_REQUEST),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(
            requireNotNull(result.response.getHeader("Location"))
                .removeSuffix("/timeline")
                .substringAfterLast('/'),
        )
    }

    private fun attest(
        tenantId: UUID,
        actorId: UUID,
        authority: String,
        caseId: UUID?,
    ): UUID {
        val result =
            mockMvc
                .perform(
                    post(ACTOR_EVIDENCE_PATH, tenantId, actorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(attestationRequest(authority, caseId, TEST_NOW.plus(1, ChronoUnit.HOURS))),
                ).andExpect(status().isCreated)
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andReturn()
        return UUID.fromString(requireNotNull(result.response.getHeader("Location")).substringAfterLast('/'))
    }

    private fun expectInvalidAttestation(
        tenantId: UUID,
        actorId: UUID,
        authority: String,
        caseId: UUID?,
    ) {
        mockMvc
            .perform(
                post(ACTOR_EVIDENCE_PATH, tenantId, actorId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(attestationRequest(authority, caseId, TEST_NOW.plus(1, ChronoUnit.HOURS))),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("urn:ergon:problem:invalid-human-authority-request"))
    }

    private fun assertImmutable(
        table: String,
        idColumn: String,
        id: UUID,
    ) {
        assertThatThrownBy {
            // Table and column are test-owned constants, while values remain bound parameters.
            jdbcClient
                .sql("UPDATE $table SET recorded_at = recorded_at WHERE $idColumn = :id")
                .param("id", id)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun assertDatabaseRejectsInvalidScope(
        tenantId: UUID,
        actorId: UUID,
        caseId: UUID,
    ) {
        assertThatThrownBy {
            jdbcClient
                .sql(
                    """
                    INSERT INTO approval_authority_evidence (
                        tenant_id, evidence_id, actor_id, authority, case_id,
                        source_provider, source_reference, attested_at, expires_at
                    ) VALUES (
                        :tenantId, :evidenceId, :actorId, 'RESOLVER', :caseId,
                        'workforce-sso', 'claims/invalid-scope', :attestedAt, :expiresAt
                    )
                    """.trimIndent(),
                ).param("tenantId", tenantId)
                .param("evidenceId", UUID.randomUUID())
                .param("actorId", actorId)
                .param("caseId", caseId)
                .param("attestedAt", TEST_NOW)
                .param("expiresAt", TEST_NOW.plus(1, ChronoUnit.HOURS))
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun attestationRequest(
        authority: String,
        caseId: UUID?,
        expiresAt: Instant,
    ): String =
        """
        {
          "authority": "$authority",
          "caseId": ${caseId?.let { "\"$it\"" } ?: "null"},
          "sourceProvider": "workforce-sso",
          "sourceReference": "claims/authority-42",
          "expiresAt": "$expiresAt"
        }
        """.trimIndent()

    companion object {
        private const val ACTORS_PATH = "/internal/v1/tenants/{tenantId}/human-actors"
        private const val ACTOR_PATH = "$ACTORS_PATH/{actorId}"
        private const val ACTOR_EVIDENCE_PATH = "$ACTOR_PATH/approval-authority-evidence"
        private const val EVIDENCE_PATH = "$ACTORS_PATH/approval-authority-evidence/{evidenceId}"
        private const val ACTOR_REQUEST =
            """{"identityProvider":"workforce-sso","subject":"employee-42"}"""
        private const val OPEN_CASE_REQUEST =
            """{"goal":"Restore workspace access","initialObservation":"I cannot sign in."}"""
        private val TEST_NOW = Instant.parse("2026-09-14T10:00:00Z")

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

@TestConfiguration(proxyBeanMethods = false)
private class FixedHumanAuthorityClockConfiguration {
    @Bean
    @Primary
    fun fixedHumanAuthorityClock(): Clock = Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC)
}
