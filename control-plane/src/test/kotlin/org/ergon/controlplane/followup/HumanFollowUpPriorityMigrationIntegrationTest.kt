package org.ergon.controlplane.followup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.resolution.RunSupervisionDatabaseFixture
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real integrity and lock behavior; no Spring context or public producer is needed for this schema foundation. */
@Testcontainers(disabledWithoutDocker = true)
class HumanFollowUpPriorityMigrationIntegrationTest {
    @Test
    fun `empty chain validates and released schema upgrade preserves existing source and ownership`() {
        val schema = "priority_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        flyway(schema, "20261023195734").migrate()
        val f = fixture(schema)
        val before = snapshot(f)
        val current = flyway(schema)
        current.migrate()
        current.validate()
        assertThat(
            current
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261024101559")
        assertThat(snapshot(f)).isEqualTo(before)
        assertThat(history(f)).isZero()
        assign(f)
        assertThat(history(f)).isEqualTo(1)
    }

    @Test
    fun `all priorities and a repeated value append independent revisions without rewriting source`() {
        val f = fixture()
        val before = snapshot(f)
        for ((index, priority) in listOf("LOW", "NORMAL", "HIGH", "URGENT", "URGENT").withIndex()) {
            assign(f, mapOf("revision" to index + 1L, "priority" to priority))
        }
        assertThat(history(f)).isEqualTo(5)
        connection().use { c ->
            assertThat(
                client(c)
                    .sql(
                        """
                        SELECT priority FROM human_follow_up_priority_assignments
                        WHERE tenant_id = :tenant AND work_item_id = :work ORDER BY priority_revision
                        """.trimIndent(),
                    ).params(parameters(f))
                    .query(String::class.java)
                    .list(),
            ).containsExactly("LOW", "NORMAL", "HIGH", "URGENT", "URGENT")
        }
        assertThat(snapshot(f)).isEqualTo(before)
    }

    @Test
    fun `invalid values gaps stale revisions and mismatched attribution cannot append`() {
        val f = fixture()
        val foreign = fixture()
        for (changes in listOf(
            mapOf("priority" to "CRITICAL"),
            mapOf("revision" to 0L),
            mapOf("revision" to 2L),
            mapOf("tenant" to foreign.graph.tenant.value),
            mapOf("work" to foreign.work),
            mapOf("claim" to UUID.randomUUID()),
            mapOf("ownershipRevision" to 3L),
            mapOf("actor" to f.graph.incoming.value),
            mapOf("evidence" to f.graph.incomingEvidence.value),
            mapOf("evidence" to foreign.graph.outgoingEvidence.value),
        )) {
            assertThatThrownBy { assign(f, changes) }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(history(f)).isZero()
        assign(f)
        assertThatThrownBy { assign(f) }.isInstanceOf(DataAccessException::class.java)
        assertThat(history(f)).isEqualTo(1)
    }

    @Test
    fun `command identity is unique and audit cannot be edited or deleted`() {
        val f = fixture()
        val command = UUID.randomUUID()
        assign(f, mapOf("command" to command))
        assertThatThrownBy { assign(f, mapOf("revision" to 2L, "command" to command)) }
            .isInstanceOf(DataAccessException::class.java)
        connection(f.schema).use { c ->
            for (sql in listOf(
                "UPDATE human_follow_up_priority_assignments SET priority = priority",
                "DELETE FROM human_follow_up_priority_assignments",
            )) {
                assertThatThrownBy {
                    client(c)
                        .sql("$sql WHERE tenant_id = :tenant AND work_item_id = :work")
                        .params(parameters(f))
                        .update()
                }.isInstanceOf(DataAccessException::class.java)
            }
        }
        assertThat(history(f)).isEqualTo(1)
    }

    @Test
    fun `rollback retains revision zero and identical work identities remain tenant isolated`() {
        val f = fixture()
        val other = fixture(work = f.work)
        connection().use { c ->
            c.autoCommit = false
            assign(c, f)
            c.rollback()
        }
        assertThat(history(f)).isZero()
        assign(f)
        assign(other, mapOf("priority" to "LOW"))
        assertThat(history(f)).isEqualTo(1)
        assertThat(history(other)).isEqualTo(1)
    }

    @Test
    fun `terminal or released work cannot acquire a priority assignment`() {
        val terminal = fixture()
        connection().use { complete(it, terminal) }
        assertThatThrownBy { assign(terminal) }.isInstanceOf(DataAccessException::class.java)
        val released = fixture()
        connection().use { c ->
            client(c)
                .sql(
                    """
                    INSERT INTO human_follow_up_ownership_events
                        (tenant_id, work_item_id, ownership_revision, event_type, claim_id, resolver_actor_id, authority_evidence_id, occurred_at)
                    VALUES (:tenant, :work, 2, 'RELEASED', :claim, :actor, :evidence, :at)
                    """.trimIndent(),
                ).params(parameters(released))
                .update()
            client(c)
                .sql(
                    """
                    UPDATE human_follow_up_current_ownership
                    SET ownership_revision = 2, current_claim_id = NULL,
                        current_resolver_actor_id = NULL, current_claimed_at = NULL
                    WHERE tenant_id = :tenant AND work_item_id = :work
                    """.trimIndent(),
                ).params(parameters(released))
                .update()
        }
        assertThatThrownBy { assign(released) }.isInstanceOf(DataAccessException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["priority", "completion"])
    fun `waiting insert observes committed priority or terminal state after the shared work lock`(holder: String) {
        val f = fixture()
        val pid = AtomicInteger()
        Executors.newSingleThreadExecutor().use { executor ->
            connection().use { c ->
                c.autoCommit = false
                if (holder == "priority") assign(c, f) else complete(c, f)
                val pending =
                    executor.submit<Boolean> {
                        connection().use { waiting ->
                            pid.set(client(waiting).sql("SELECT pg_backend_pid()").query(Int::class.java).single())
                            assertThatThrownBy { assign(waiting, f) }
                                .isInstanceOf(DataAccessException::class.java)
                            true
                        }
                    }
                try {
                    // PostgreSQL must report a real blocker before the first transaction commits.
                    await().atMost(20, TimeUnit.SECONDS).until {
                        pid.get() != 0 &&
                            client(c)
                                .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
                                .param("pid", pid.get())
                                .query(Boolean::class.java)
                                .single()
                    }
                    c.commit()
                    assertThat(pending.get(20, TimeUnit.SECONDS)).isTrue()
                } finally {
                    c.rollback()
                }
            }
        }
        assertThat(history(f)).isEqualTo(if (holder == "priority") 1L else 0L)
    }

    private fun assign(
        f: Fixture,
        changes: Map<String, Any> = emptyMap(),
    ) {
        connection(f.schema).use { assign(it, f, changes) }
    }

    private fun assign(
        c: Connection,
        f: Fixture,
        changes: Map<String, Any> = emptyMap(),
    ) {
        client(c)
            .sql(
                """
                INSERT INTO human_follow_up_priority_assignments (
                    tenant_id, work_item_id, priority_revision, command_id, priority,
                    claim_id, ownership_revision, actor_id, authority_evidence_id, assigned_at
                ) VALUES (:tenant, :work, :revision, :command, :priority, :claim,
                    :ownershipRevision, :actor, :evidence, :at)
                """.trimIndent(),
            ).params(
                parameters(f) + mapOf("revision" to 1L, "priority" to "HIGH", "command" to UUID.randomUUID()) + changes,
            ).update()
    }

    private fun complete(
        c: Connection,
        f: Fixture,
    ) {
        client(c)
            .sql(
                """
                INSERT INTO human_follow_up_completions
                    (tenant_id, work_item_id, command_id, claim_id, ownership_revision, actor_id, authority_evidence_id, completed_at)
                VALUES (:tenant, :work, :command, :claim, 1, :actor, :evidence, :at)
                """.trimIndent(),
            ).params(parameters(f) + ("command" to UUID.randomUUID()))
            .update()
    }

    private fun history(f: Fixture): Long =
        connection(f.schema).use { c ->
            client(c)
                .sql(
                    """
                    SELECT COUNT(*) FROM human_follow_up_priority_assignments
                    WHERE tenant_id = :tenant AND work_item_id = :work
                    """.trimIndent(),
                ).params(parameters(f))
                .query(Long::class.java)
                .single()
        }

    private fun snapshot(f: Fixture): String =
        connection(f.schema).use { c ->
            client(c)
                .sql(
                    """
                    SELECT jsonb_build_object('work', to_jsonb(item), 'run', to_jsonb(run),
                        'case', to_jsonb(case_row), 'state', to_jsonb(state), 'ownership', to_jsonb(ownership),
                        'proofs', (SELECT COUNT(*) FROM resolution_outcome_proof_acceptances proof
                            WHERE proof.tenant_id = run.tenant_id AND proof.run_id = run.run_id))::TEXT
                    FROM human_follow_up_work_items item
                    JOIN resolution_runs run ON run.tenant_id = item.tenant_id AND run.run_id = item.run_id
                    JOIN cases case_row ON case_row.tenant_id = run.tenant_id AND case_row.case_id = run.case_id
                    JOIN resolution_run_states state ON state.tenant_id = run.tenant_id AND state.run_id = run.run_id
                    JOIN human_follow_up_current_ownership ownership
                        ON ownership.tenant_id = item.tenant_id
                        AND ownership.work_item_id = item.work_item_id
                    WHERE item.tenant_id = :tenant AND item.work_item_id = :work
                    """.trimIndent(),
                ).params(parameters(f))
                .query(String::class.java)
                .single()
        }

    private fun parameters(f: Fixture): Map<String, Any> =
        mapOf(
            "tenant" to f.graph.tenant.value,
            "work" to f.work,
            "claim" to f.claim,
            "actor" to f.graph.outgoing.value,
            "evidence" to f.graph.outgoingEvidence.value,
            "ownershipRevision" to 1L,
            "at" to NOW.atOffset(ZoneOffset.UTC),
        )

    private fun fixture(
        schema: String = "public",
        work: UUID = UUID.randomUUID(),
    ): Fixture =
        connection(schema).use { c ->
            c.autoCommit = false
            val jdbc = client(c)
            val graph = RunSupervisionDatabaseFixture.seed(jdbc, NOW)
            // Only upstream escalation setup is isolated; priority, authority,
            // ownership and terminal constraints remain enabled when exercised.
            jdbc.sql("SET LOCAL session_replication_role = replica").update()
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_work_items (tenant_id, work_item_id, run_id, escalation_event_id, reason, status, opened_at)
                    VALUES (:tenant, :work, :run, :event, 'RETRY_ATTEMPT_LIMIT_REACHED', 'OPEN', :at)
                    """.trimIndent(),
                ).param("tenant", graph.tenant.value)
                .param("work", work)
                .param("run", graph.run.value)
                .param("event", UUID.randomUUID())
                .param("at", NOW.atOffset(ZoneOffset.UTC))
                .update()
            jdbc.sql("SET LOCAL session_replication_role = origin").update()
            val f = Fixture(schema, graph, work, UUID.randomUUID())
            jdbc
                .sql(
                    """
                    INSERT INTO human_follow_up_claims
                        (tenant_id, claim_id, work_item_id, resolver_actor_id, authority_evidence_id, claimed_at)
                    VALUES (:tenant, :claim, :work, :actor, :evidence, :at)
                    """.trimIndent(),
                ).params(parameters(f))
                .update()
            c.commit()
            f
        }

    private data class Fixture(
        val schema: String,
        val graph: RunSupervisionDatabaseFixture,
        val work: UUID,
        val claim: UUID,
    )

    companion object {
        private val NOW = Instant.parse("2026-10-24T10:00:00Z")

        @Container @JvmStatic
        val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withEnv("POSTGRES_INITDB_ARGS", "--no-sync")

        private fun connection(schema: String = "public"): Connection =
            DriverManager
                .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { this.schema = schema }

        private fun client(connection: Connection): JdbcClient =
            JdbcClient
                .create(SingleConnectionDataSource(connection, true))

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

        @BeforeAll @JvmStatic
        fun migrateEmptyDatabase() {
            val current = flyway("public")
            current.migrate()
            current.validate()
        }
    }
}
