package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationFixtureJsonCodec
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.SimulationFixtureConflict
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureRegistration
import org.ergon.controlplane.evaluation.application.SimulationFixtureUnavailable
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
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class SimulationFixtureIntegrationTest {
    private val mapper = JsonMapper.builder().build()
    private val codec = SimulationFixtureJsonCodec(mapper)

    @Test
    fun `upgrade retains actors and registers immutable isolated packages with original retry attribution`() {
        val schema = "fixture_" + UUID.randomUUID().toString().replace("-", "")
        flyway(schema, "20261030120000").migrate()
        val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
        val data =
            DriverManagerDataSource(
                postgres.jdbcUrl + "${separator}currentSchema=$schema",
                postgres.username,
                postgres.password,
            )
        val jdbc = JdbcClient.create(data)
        val tenant = UUID.randomUUID()
        val actor = actor(jdbc, tenant)
        val otherActor = actor(jdbc, tenant)
        val foreignActor = actor(jdbc, UUID.randomUUID())
        flyway(schema).migrate()
        flyway(schema).validate()
        val repository =
            PostgresSimulationFixtureRepository(
                jdbc,
                TransactionTemplate(DataSourceTransactionManager(data)),
                codec,
            )
        val document = SimulationPackageFixtures.document(tenant)
        val prepared = codec.decode(mapper.writeValueAsBytes(document))
        val identity = SimulationFixtureIdentity(tenant, "reviewed-case", "1")
        val created = repository.register(identity, prepared, actor)
        assertThat(created.created).isTrue()
        val retry = repository.register(identity, prepared, otherActor)
        assertThat(retry.created).isFalse()
        assertThat(retry.stored).isEqualTo(created.stored)
        assertThat(repository.find(identity)).isEqualTo(created.stored)
        assertThat(repository.find(identity.copy(tenantId = UUID.randomUUID()))).isNull()
        assertThatThrownBy { repository.register(identity, prepared, foreignActor) }
            .isInstanceOf(SimulationFixtureUnavailable::class.java)
        val changed =
            codec.decode(
                mapper.writeValueAsBytes(
                    document.copy(
                        provenance = document.provenance.copy(description = "Different historical attribution"),
                    ),
                ),
            )
        assertThatThrownBy { repository.register(identity, changed, otherActor) }
            .isInstanceOf(SimulationFixtureConflict::class.java)
        assertThat(repository.find(identity)).isEqualTo(created.stored)
        // Fresh keys exercise simultaneous insert arbitration across both unique indexes, not sequential replay.
        repeat(CONCURRENT_KEYS) { attempt ->
            concurrentRetry(repository, identity.copy(key = "concurrent-$attempt"), prepared, actor)
        }
        rejectDirectWrites(jdbc, tenant, foreignActor)
    }

    private fun rejectDirectWrites(
        jdbc: JdbcClient,
        tenant: UUID,
        foreignActor: UUID,
    ) {
        assertThatThrownBy { jdbc.sql("UPDATE simulation_fixture_revisions SET revision = revision").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.sql("DELETE FROM simulation_fixture_revisions").update() }
            .isInstanceOf(DataAccessException::class.java)
        val clone =
            """
            INSERT INTO simulation_fixture_revisions
                (tenant_id, fixture_key, revision, digest, canonical, definition, submitted_by)
            SELECT tenant_id, :key, revision, %s, canonical, definition, %s
            FROM simulation_fixture_revisions WHERE tenant_id = :tenant AND fixture_key = 'reviewed-case'
            """.trimIndent()
        assertThatThrownBy {
            jdbc
                .sql(clone.format("digest", ":actor"))
                .param("key", "foreign-actor")
                .param("tenant", tenant)
                .param("actor", foreignActor)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc
                .sql(clone.format("repeat('0', 64)", "submitted_by"))
                .param("key", "bad-digest")
                .param("tenant", tenant)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
        val count = jdbc.sql("SELECT COUNT(*) FROM simulation_fixture_revisions").query(Long::class.java).single()
        assertThat(count).isEqualTo(1L + CONCURRENT_KEYS)
    }

    private fun concurrentRetry(
        repository: PostgresSimulationFixtureRepository,
        identity: SimulationFixtureIdentity,
        prepared: org.ergon.controlplane.evaluation.application.PreparedSimulationFixture,
        actor: UUID,
    ) {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..2).map {
                    pool.submit<SimulationFixtureRegistration> {
                        check(start.await(10, TimeUnit.SECONDS))
                        repository.register(identity, prepared, actor)
                    }
                }
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results.map { it.stored }.distinct()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `full empty migration chain validates`() {
        val migration = flyway("empty_fixture_" + UUID.randomUUID().toString().replace("-", ""))
        migration.migrate()
        migration.validate()
        assertThat(
            migration
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030171119")
    }

    private fun actor(
        jdbc: JdbcClient,
        tenant: UUID,
    ): UUID {
        val actor = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'simulation-test', :subject, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("actor", actor)
            .param("subject", actor.toString())
            .update()
        return actor
    }

    private fun flyway(
        schema: String,
        target: String = "latest",
    ): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema)
            .defaultSchema(schema)
            .target(target)
            .load()

    companion object {
        private const val CONCURRENT_KEYS = 8

        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
