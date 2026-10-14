package org.ergon.controlplane.resolution

import org.assertj.core.api.Assertions.assertThat
import org.ergon.controlplane.resolution.adapter.out.persistence.PostgresRunSupervisorAssignmentRepository
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class RunSupervisionMigrationIntegrationTest {
    @Test
    fun `upgrade preserves initial assignments and creates exactly one matching current supervision epoch`() {
        val schema = "supervision_upgrade_${UUID.randomUUID().toString().replace("-", "") }"
        val previous = flyway(schema).target("20261012200000").load()
        previous.migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.autoCommit = false
            val jdbc = JdbcClient.create(SingleConnectionDataSource(connection, true))
            val fixture = RunSupervisionDatabaseFixture.seed(jdbc, NOW)
            connection.commit()
            val upgraded = flyway(schema).load()
            upgraded.migrate()
            upgraded.validate()
            val assignments = PostgresRunSupervisorAssignmentRepository(jdbc)
            assertThat(assignments.findByRun(fixture.tenant, fixture.run)).isEqualTo(fixture.initial)
            val current = requireNotNull(assignments.findAssigned(fixture.tenant, fixture.run, fixture.outgoing, NOW))
            assertThat(current.initialAssignment).isEqualTo(fixture.initial)
            assertThat(current.revision.value).isZero()
            assertThat(current.supervisorActorId).isEqualTo(fixture.outgoing)
            assertThat(current.supervisedAt).isEqualTo(NOW)
            assertThat(
                jdbc.sql("SELECT COUNT(*) FROM resolution_run_supervision_events").query(Int::class.java).single(),
            ).isEqualTo(1)
            // The compatibility trigger must initialize a later old-style writer too,
            // rather than relying only on the one-time backfill.
            RunSupervisionDatabaseFixture.seed(jdbc, NOW)
            connection.commit()
            assertThat(
                jdbc.sql("SELECT COUNT(*) FROM resolution_run_current_supervision").query(Int::class.java).single(),
            ).isEqualTo(2)
            assertThat(
                jdbc.sql("SELECT COUNT(*) FROM resolution_run_supervision_events").query(Int::class.java).single(),
            ).isEqualTo(2)
            connection.rollback()
        }
    }

    @Test
    fun `complete migration chain creates an empty supervision projection without inventing assignees`() {
        val schema = "supervision_empty_${UUID.randomUUID().toString().replace("-", "") }"
        val current = flyway(schema).load()
        current.migrate()
        current.validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            val jdbc = JdbcClient.create(SingleConnectionDataSource(connection, true))
            assertThat(
                jdbc.sql("SELECT COUNT(*) FROM resolution_run_current_supervision").query(Int::class.java).single(),
            ).isZero()
            assertThat(
                jdbc.sql("SELECT COUNT(*) FROM resolution_run_supervision_events").query(Int::class.java).single(),
            ).isZero()
        }
    }

    private fun flyway(schema: String): org.flywaydb.core.api.configuration.FluentConfiguration =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema)
            .defaultSchema(schema)
            .table("ergon_flyway_schema_history")

    companion object {
        private val NOW = Instant.parse("2026-10-14T07:00:00Z")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
