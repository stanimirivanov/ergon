package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class HumanFollowUpNoteMigrationIntegrationTest {
    @Test
    fun `handover upgrade preserves previously released ownership and internal notes`() {
        val schema = "handover_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway() =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
        flyway().target("20261023120000").load().migrate()
        val tenant = UUID.randomUUID()
        val work = UUID.randomUUID()
        seedPreviousOwnership(schema, tenant, work)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO human_follow_up_notes (tenant_id, work_item_id, note_id, ownership_revision, body, authored_at)
                    VALUES ('$tenant', '$work', '${UUID.randomUUID()}', 1, 'retained on handover upgrade', CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
            }
        }
        flyway().load().migrate()
        flyway().load().validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT ownership.ownership_revision, note.body
                        FROM human_follow_up_current_ownership ownership
                        JOIN human_follow_up_notes note USING (tenant_id, work_item_id)
                        WHERE ownership.tenant_id = '$tenant' AND ownership.work_item_id = '$work'
                        """.trimIndent(),
                    ).use { row ->
                        assertThat(row.next()).isTrue()
                        assertThat(row.getLong(1)).isEqualTo(2)
                        assertThat(row.getString(2)).isEqualTo("retained on handover upgrade")
                    }
                statement.executeQuery("SELECT COUNT(*) FROM human_follow_up_handovers").use { row ->
                    assertThat(row.next()).isTrue()
                    assertThat(row.getLong(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `upgrade preserves existing ownership and permits only claimed-event note attribution`() {
        val schema = "notes_upgrade_" + UUID.randomUUID().toString().replace("-", "")

        fun flyway() =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
        flyway().target("20261020120000").load().migrate()
        val tenant = UUID.randomUUID()
        val work = UUID.randomUUID()
        seedPreviousOwnership(schema, tenant, work)
        flyway().load().migrate()
        flyway().load().validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT ownership_revision FROM human_follow_up_current_ownership
                        WHERE tenant_id = '$tenant' AND work_item_id = '$work'
                        """.trimIndent(),
                    ).use { row ->
                        assertThat(row.next()).isTrue()
                        assertThat(row.getLong(1)).isEqualTo(2)
                    }
                assertThat(
                    statement.executeUpdate(
                        """
                        INSERT INTO human_follow_up_notes (tenant_id, work_item_id, note_id, ownership_revision, body, authored_at)
                        VALUES ('$tenant', '$work', '${UUID.randomUUID()}', 1, 'after upgrade', CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ),
                ).isEqualTo(1)
                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        INSERT INTO human_follow_up_notes (tenant_id, work_item_id, note_id, ownership_revision, body, authored_at)
                        VALUES ('$tenant', '$work', '${UUID.randomUUID()}', 2, 'released event is not authorship', CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    )
                }.isInstanceOf(java.sql.SQLException::class.java)
            }
        }
    }

    private fun seedPreviousOwnership(
        schema: String,
        tenant: UUID,
        work: UUID,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.schema = schema
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                // Only earlier source-graph FKs are bypassed in this upgrade fixture.
                statement.execute("SET LOCAL session_replication_role = replica")
                statement.execute(
                    """
                    INSERT INTO human_follow_up_work_items (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                    VALUES ('$tenant', '$work', '${UUID.randomUUID()}', '${UUID.randomUUID()}', 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO human_follow_up_ownership_events (tenant_id, work_item_id, ownership_revision, event_type, claim_id, resolver_actor_id, authority_evidence_id, occurred_at)
                    VALUES ('$tenant', '$work', 1, 'CLAIMED', '${UUID.randomUUID()}', '${UUID.randomUUID()}', '${UUID.randomUUID()}', CURRENT_TIMESTAMP)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO human_follow_up_current_ownership (tenant_id, work_item_id, ownership_revision, current_claim_id, current_resolver_actor_id, current_claimed_at)
                    SELECT tenant_id, work_item_id, ownership_revision, claim_id, resolver_actor_id, occurred_at
                    FROM human_follow_up_ownership_events WHERE tenant_id = '$tenant' AND work_item_id = '$work' AND ownership_revision = 1
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO human_follow_up_ownership_events (tenant_id, work_item_id, ownership_revision, event_type, claim_id, resolver_actor_id, authority_evidence_id, occurred_at)
                    SELECT tenant_id, work_item_id, 2, 'RELEASED', claim_id, resolver_actor_id, authority_evidence_id, occurred_at
                    FROM human_follow_up_ownership_events WHERE tenant_id = '$tenant' AND work_item_id = '$work' AND ownership_revision = 1
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    UPDATE human_follow_up_current_ownership
                    SET ownership_revision = 2, current_claim_id = NULL, current_resolver_actor_id = NULL, current_claimed_at = NULL
                    WHERE tenant_id = '$tenant' AND work_item_id = '$work'
                    """.trimIndent(),
                )
            }
            connection.commit()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
