package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class HumanFollowUpMigrationIntegrationTest {
    @Test
    fun `upgrade backfills one work item for an existing escalation`() {
        val schema = "follow_up_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        flyway(schema).target(PREVIOUS_VERSION).load().migrate()
        seedExistingEscalation(schema)
        flyway(schema).load().migrate()
        assertBackfilledWorkItem(schema)
        assertInboxIndex(schema)
    }

    private fun seedExistingEscalation(schema: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                // The fixture isolates this migration's backfill contract; source
                // tables already proved their own foreign keys in earlier slices.
                statement.execute("SET session_replication_role = replica")
                insertRun(statement)
                insertEscalation(statement)
                statement.execute("SET session_replication_role = origin")
            }
        }
    }

    private fun insertRun(statement: Statement) {
        statement.executeUpdate(
            """
            INSERT INTO resolution_runs (
                tenant_id, run_id, case_id, case_stream_version,
                contract_key, contract_revision, policy_revision,
                step_id, capability, effective_risk, required_approval,
                initial_state, attempt_number
            ) VALUES (
                '$TENANT_ID', '$RUN_ID', '$CASE_ID', 1,
                'restore-workspace-access', 1, 'ergon.dev/policy/access-restoration/v1',
                'unlock-account', 'identity.account.unlock', 'HIGH', 'RESOLVER',
                'WAITING_FOR_APPROVAL', 1
            )
            """.trimIndent(),
        )
    }

    private fun insertEscalation(statement: Statement) {
        statement.executeUpdate(
            """
            INSERT INTO resolution_run_events (
                tenant_id, run_id, sequence, event_id, event_type,
                from_state, to_state, authorization_consumption_id,
                receipt_outcome, occurred_at, escalation_actor_id,
                escalation_authority_evidence_id, escalation_reason,
                escalation_policy_revision, escalation_source_attempt_number,
                escalation_maximum_attempts
            ) VALUES (
                '$TENANT_ID', '$RUN_ID', 2, '$ESCALATION_EVENT_ID',
                'ESCALATION_REQUESTED', 'ACTION_FAILED', 'ESCALATED', NULL,
                NULL, '2026-09-16T12:00:00Z', '$ACTOR_ID', '$AUTHORITY_EVIDENCE_ID',
                'RETRY_ATTEMPT_LIMIT_REACHED', 'ergon.dev/policy/resolution-retry/v1',
                1, 1
            )
            """.trimIndent(),
        )
    }

    private fun assertBackfilledWorkItem(schema: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT work_item_id, reason, status, opened_at
                        FROM human_follow_up_work_items
                        WHERE tenant_id = '$TENANT_ID' AND run_id = '$RUN_ID'
                        """.trimIndent(),
                    ).use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getObject("work_item_id", UUID::class.java)).isNotNull()
                        assertThat(result.getString("reason")).isEqualTo("RETRY_ATTEMPT_LIMIT_REACHED")
                        assertThat(result.getString("status")).isEqualTo("OPEN")
                        assertThat(result.getObject("opened_at", OffsetDateTime::class.java).toInstant())
                            .isEqualTo(Instant.parse("2026-09-16T12:00:00Z"))
                        assertThat(result.next()).isFalse()
                    }
            }
        }
    }

    private fun assertInboxIndex(schema: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection
                .prepareStatement(
                    """
                    SELECT indexdef
                    FROM pg_indexes
                    WHERE schemaname = ? AND indexname = 'ix_human_follow_up_work_items_open_inbox'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, schema)
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString("indexdef"))
                            .contains("tenant_id, opened_at, work_item_id")
                            .contains("WHERE (status = 'OPEN'::text)")
                        assertThat(result.next()).isFalse()
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

    private companion object {
        private const val PREVIOUS_VERSION = "20260916010000"
        private const val TENANT_ID = "11111111-1111-1111-1111-111111111111"
        private const val RUN_ID = "22222222-2222-2222-2222-222222222222"
        private const val CASE_ID = "33333333-3333-3333-3333-333333333333"
        private const val ESCALATION_EVENT_ID = "44444444-4444-4444-4444-444444444444"
        private const val ACTOR_ID = "55555555-5555-5555-5555-555555555555"
        private const val AUTHORITY_EVIDENCE_ID = "66666666-6666-6666-6666-666666666666"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
