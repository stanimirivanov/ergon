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
    fun `control upgrade preserves released supervision and decisions without fabricating leases`() {
        val schema = "control_upgrade_${UUID.randomUUID().toString().replace("-", "") }"
        flyway(schema).target("20261018120000").load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.autoCommit = false
            val jdbc = JdbcClient.create(SingleConnectionDataSource(connection, true))
            val fixture = RunSupervisionDatabaseFixture.seed(jdbc, NOW)
            insertLegacyDecision(jdbc, fixture)
            connection.commit()
            val upgraded = flyway(schema).load()
            upgraded.migrate()
            upgraded.validate()
            val assignments = PostgresRunSupervisorAssignmentRepository(jdbc)
            assertThat(assignments.findAssigned(fixture.tenant, fixture.run, fixture.outgoing, NOW)?.revision?.value)
                .isZero()
            assertThat(jdbc.sql("SELECT COUNT(*) FROM resolution_approval_decisions").query(Int::class.java).single())
                .isEqualTo(1)
            val controlTables =
                listOf(
                    "resolution_run_control_events",
                    "resolution_run_control_heads",
                    "resolution_run_control_admissions",
                )
            for (table in controlTables) {
                assertThat(jdbc.sql("SELECT COUNT(*) FROM $table").query(Int::class.java).single()).isZero()
            }
            // Unchanged trusted writers remain usable: the browser flag is not a global executor fence.
            insertLegacyDecision(jdbc, RunSupervisionDatabaseFixture.seed(jdbc, NOW))
            connection.commit()
            assertThat(jdbc.sql("SELECT COUNT(*) FROM resolution_approval_decisions").query(Int::class.java).single())
                .isEqualTo(2)
        }
    }

    @Test
    fun `approval command upgrade preserves legacy decisions and supports unchanged bearer writers`() {
        val schema = "approval_upgrade_${UUID.randomUUID().toString().replace("-", "") }"
        flyway(schema).target("20261014072706").load().migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.autoCommit = false
            val jdbc = JdbcClient.create(SingleConnectionDataSource(connection, true))
            val first = RunSupervisionDatabaseFixture.seed(jdbc, NOW)
            insertLegacyDecision(jdbc, first)
            connection.commit()
            val before =
                jdbc
                    .sql(
                        "SELECT decided_at FROM resolution_approval_decisions",
                    ).query(java.time.OffsetDateTime::class.java)
                    .single()
            // End the observation transaction before Flyway's separate
            // connection acquires the schema lock for the unique constraint.
            connection.commit()
            val upgraded = flyway(schema).load()
            upgraded.migrate()
            upgraded.validate()
            assertThat(
                jdbc
                    .sql("SELECT decided_at FROM resolution_approval_decisions")
                    .query(java.time.OffsetDateTime::class.java)
                    .single(),
            ).isEqualTo(before)
            assertThat(
                jdbc
                    .sql("SELECT COUNT(*) FROM resolution_run_approval_commands")
                    .query(Int::class.java)
                    .single(),
            ).isZero()
            insertLegacyDecision(jdbc, RunSupervisionDatabaseFixture.seed(jdbc, NOW))
            connection.commit()
            assertThat(
                jdbc
                    .sql("SELECT COUNT(*) FROM resolution_approval_decisions")
                    .query(Int::class.java)
                    .single(),
            ).isEqualTo(2)
            assertThat(
                jdbc
                    .sql("SELECT COUNT(*) FROM resolution_run_approval_commands")
                    .query(Int::class.java)
                    .single(),
            ).isZero()
        }
    }

    private fun insertLegacyDecision(
        jdbc: JdbcClient,
        fixture: RunSupervisionDatabaseFixture,
    ) {
        val request = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO resolution_approval_requests (tenant_id, approval_request_id, run_id, step_id, required_authority, requested_at, expires_at)
                SELECT tenant_id, :request, run_id, step_id, required_approval, :at, :expiry
                FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run
                """.trimIndent(),
            ).param("request", request)
            .param("tenant", fixture.tenant.value)
            .param("run", fixture.run.value)
            .param(
                "at",
                NOW.atOffset(java.time.ZoneOffset.UTC),
            ).param("expiry", NOW.plusSeconds(900).atOffset(java.time.ZoneOffset.UTC))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO resolution_approval_decisions (tenant_id, approval_decision_id, approval_request_id, run_id,
                    case_id, actor_id, evidence_id, authority, outcome, decided_at)
                SELECT tenant_id, :decision, :request, run_id, case_id, :actor, :evidence, 'RESOLVER', 'APPROVED', :at
                FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run
                """.trimIndent(),
            ).param("decision", UUID.randomUUID())
            .param("request", request)
            .param("tenant", fixture.tenant.value)
            .param("run", fixture.run.value)
            .param("actor", fixture.outgoing.value)
            .param("evidence", fixture.outgoingEvidence.value)
            .param("at", NOW.atOffset(java.time.ZoneOffset.UTC))
            .update()
    }

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
            assertThat(
                jdbc
                    .sql("SELECT COUNT(*) FROM resolution_run_approval_commands")
                    .query(Int::class.java)
                    .single(),
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
