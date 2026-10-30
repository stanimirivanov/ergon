package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresEvaluationReportRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationExecution
import org.ergon.controlplane.evaluation.application.EvaluationReportConflict
import org.ergon.controlplane.evaluation.application.EvaluationReportRegistration
import org.ergon.controlplane.evaluation.application.EvaluationReportUnavailable
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class EvaluationReportIntegrationTest {
    @Test
    fun `upgrade retains fixtures and stores immutable reproducible tenant scoped command reports`() {
        val schema = "reports_" + UUID.randomUUID().toString().replace("-", "")
        flyway(schema, "20261030130000").migrate()
        val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
        val data =
            DriverManagerDataSource(
                postgres.jdbcUrl + "${separator}currentSchema=$schema",
                postgres.username,
                postgres.password,
            )
        val jdbc = JdbcClient.create(data)
        val transactions = TransactionTemplate(DataSourceTransactionManager(data))
        val f = EvaluationReportFixtures()
        val tenant = f.f.tenant
        val actor = actor(jdbc, tenant)
        val otherActor = actor(jdbc, tenant)
        val foreignActor = actor(jdbc, UUID.randomUUID())
        val fixtures = PostgresSimulationFixtureRepository(jdbc, transactions, f.packages)
        val identity = SimulationFixtureIdentity(tenant, "sample", "1")
        val preparedFixture = f.packages.decode(f.bytes())
        val original = fixtures.register(identity, preparedFixture, actor)
        flyway(schema).migrate()
        flyway(schema).validate()
        assertThat(fixtures.find(identity)).isEqualTo(original.stored)
        val reports = PostgresEvaluationReportRepository(jdbc, transactions, f.codec)
        val service = EvaluationExecution(fixtures, f.runner, reports)
        val command = EvaluationCommand(identity, UUID.randomUUID())
        val first = service.execute(command, actor)
        assertThat(first.created).isTrue()
        assertThat(first.stored.passed).isTrue()
        val retry = service.execute(command, otherActor)
        assertThat(retry.created).isFalse()
        assertThat(retry.stored).isEqualTo(first.stored)
        val fresh = service.execute(command.copy(id = UUID.randomUUID()), otherActor)
        assertThat(fresh.stored.reportDigest).isEqualTo(first.stored.reportDigest)
        assertThat(fresh.stored.definition).isEqualTo(first.stored.definition)
        assertThat(reports.find(UUID.randomUUID(), command.id)).isNull()
        assertThatThrownBy { service.execute(command, foreignActor) }
            .isInstanceOf(EvaluationReportUnavailable::class.java)
        val secondIdentity = identity.copy(key = "second")
        fixtures.register(secondIdentity, preparedFixture, actor)
        assertThatThrownBy { service.execute(command.copy(fixture = secondIdentity), actor) }
            .isInstanceOf(EvaluationReportConflict::class.java)
        concurrent(service, command.copy(id = UUID.randomUUID()), actor)
        assertThatThrownBy { jdbc.sql("UPDATE simulation_evaluation_reports SET passed = NOT passed").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.sql("DELETE FROM simulation_evaluation_reports").update() }
            .isInstanceOf(DataAccessException::class.java)
        rejectCorruptClones(jdbc, command, foreignActor, actor)
        val failedDocument = f.document(listOf(f.check(expected = "FAILED")))
        val failedIdentity = identity.copy(key = "failed")
        fixtures.register(failedIdentity, f.packages.decode(f.bytes(failedDocument)), actor)
        val failed = service.execute(EvaluationCommand(failedIdentity, UUID.randomUUID()), actor)
        assertThat(failed.stored.passed).isFalse()
        assertThat(service.get(tenant, failed.stored.command.id)).isEqualTo(failed.stored)
    }

    private fun concurrent(
        service: EvaluationExecution,
        command: EvaluationCommand,
        actor: UUID,
    ) {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<EvaluationReportRegistration> {
                        check(start.await(10, TimeUnit.SECONDS))
                        service.execute(command, actor)
                    }
                }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results.map { it.stored }.distinct()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun rejectCorruptClones(
        jdbc: JdbcClient,
        command: EvaluationCommand,
        foreignActor: UUID,
        actor: UUID,
    ) {
        val sql =
            """
            INSERT INTO simulation_evaluation_reports
                (tenant_id, evaluation_id, fixture_key, fixture_revision, fixture_digest, report_digest, definition, passed, submitted_by)
            SELECT tenant_id, :newId, fixture_key, fixture_revision, %s, %s, definition, passed, %s
            FROM simulation_evaluation_reports WHERE tenant_id = :tenant AND evaluation_id = :id
            """.trimIndent()
        listOf(
            sql.format("fixture_digest", "report_digest", ":actor") to foreignActor,
            sql.format("repeat('0', 64)", "report_digest", ":actor") to actor,
            sql.format("fixture_digest", "repeat('0', 64)", ":actor") to actor,
        ).forEach { (query, submitter) ->
            assertThatThrownBy {
                jdbc
                    .sql(query)
                    .param("newId", UUID.randomUUID())
                    .param("tenant", command.fixture.tenantId)
                    .param("id", command.id)
                    .param("actor", submitter)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `complete migration chain validates from empty schema`() {
        val migration = flyway("empty_reports_" + UUID.randomUUID().toString().replace("-", ""))
        migration.migrate()
        migration.validate()
        assertThat(
            migration
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030153650")
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
                VALUES (:tenant, :actor, 'report-test', :subject, CURRENT_TIMESTAMP)
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
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
