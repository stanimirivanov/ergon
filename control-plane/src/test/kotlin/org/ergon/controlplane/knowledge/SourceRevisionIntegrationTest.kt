package org.ergon.controlplane.knowledge

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.knowledge.adapter.out.persistence.PostgresSourceRevisionRepository
import org.ergon.controlplane.knowledge.application.SourceConflict
import org.ergon.evidence.domain.EvidenceSource
import org.ergon.evidence.domain.EvidenceSourceFormat
import org.ergon.evidence.domain.SourceId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class SourceRevisionIntegrationTest {
    @Test
    fun `upgrade preserves actors and exact retry preserves original attribution while storage stays immutable`() {
        val schema = "source_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway(target: String): Flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load()
        flyway("20261028111302").migrate()
        val data =
            DriverManagerDataSource(
                postgres.jdbcUrl + "?currentSchema=$schema",
                postgres.username,
                postgres.password,
            )
        val jdbc = JdbcClient.create(data)
        val tenant = TenantId(UUID.randomUUID())
        val actor = HumanActorId(UUID.randomUUID())
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'test', 'subject', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("actor", actor.value)
            .update()
        flyway("latest").migrate()
        flyway("latest").validate()
        val repository = PostgresSourceRevisionRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(data)))
        val source =
            EvidenceSource.fromUtf8(
                tenant,
                SourceId(UUID.randomUUID()),
                EvidenceSourceFormat.COMMONMARK,
                "# 😀\r\n".toByteArray(),
            )
        val created = repository.register(source, actor)
        assertThat(created.created).isTrue()
        concurrentReplay(repository, source, actor)
        val replay = repository.register(source, actor)
        assertThat(replay.created).isFalse()
        assertThat(replay.stored).isEqualTo(created.stored)
        assertThat(repository.find(source.revision)).isEqualTo(created.stored)
        assertThat(repository.find(source.revision.copy(tenantId = TenantId(UUID.randomUUID())))).isNull()
        val otherFormat =
            EvidenceSource.fromUtf8(
                tenant,
                source.revision.sourceId,
                EvidenceSourceFormat.OPENAPI_3_0_JSON,
                source.text.toByteArray(),
            )
        assertThatThrownBy { repository.register(otherFormat, actor) }.isInstanceOf(SourceConflict::class.java)
        rejectMutation(jdbc)
        val foreign =
            EvidenceSource.fromUtf8(
                TenantId(UUID.randomUUID()),
                source.revision.sourceId,
                source.format,
                byteArrayOf(),
            )
        assertThatThrownBy { repository.register(foreign, actor) }.isInstanceOf(DataAccessException::class.java)
    }

    private fun rejectMutation(jdbc: JdbcClient) {
        assertThatThrownBy { jdbc.sql("UPDATE knowledge_source_revisions SET format = 'COMMONMARK'").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.sql("DELETE FROM knowledge_source_revisions").update() }
            .isInstanceOf(DataAccessException::class.java)
    }

    private fun concurrentReplay(
        repository: PostgresSourceRevisionRepository,
        source: EvidenceSource,
        actor: HumanActorId,
    ) {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val id = SourceId(UUID.randomUUID())
            val next = EvidenceSource.fromUtf8(source.revision.tenantId, id, source.format, byteArrayOf())
            val futures =
                (1..2).map {
                    pool.submit<org.ergon.controlplane.knowledge.application.SourceRegistration> {
                        repository.register(next, actor)
                    }
                }
            assertThat(futures.map { it.get(10, TimeUnit.SECONDS) }.count { it.created }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `empty database migrates and validates`() {
        val schema = "empty_" + UUID.randomUUID().toString().replace("-", "")
        val flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .load()
        flyway.migrate()
        flyway.validate()
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
