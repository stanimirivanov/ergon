package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresEvaluationReportRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresGateDocumentRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationFixtureRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PublishedEvaluationContractAdapter
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationExecution
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentConflict
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.GateDocumentUnavailable
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class EvaluationSuiteIntegrationTest {
    @Test
    @Suppress("LongMethod") // One upgrade journey proves the complete immutable reference graph and eligibility read.
    fun `upgrade retains reports and registers tenant safe immutable gate revisions with concurrent retry`() {
        val schema = "suite_" + UUID.randomUUID().toString().replace("-", "")
        flyway(schema, "20261030153650").migrate()
        val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
        val data =
            DriverManagerDataSource(
                postgres.jdbcUrl + "${separator}currentSchema=$schema",
                postgres.username,
                postgres.password,
            )
        val jdbc = JdbcClient.create(data)
        val transactions = TransactionTemplate(DataSourceTransactionManager(data))
        val h = SuiteHarness()
        actor(jdbc, h.tenant, h.actor)
        val other = UUID.randomUUID()
        actor(jdbc, h.tenant, other)
        val foreign = UUID.randomUUID()
        actor(jdbc, UUID.randomUUID(), foreign)
        val fixtures = PostgresSimulationFixtureRepository(jdbc, transactions, h.f.packages)
        fixtures.register(h.fixtureId, h.fixture, h.actor)
        val contractCodec = ResolutionContractJsonCodec(h.f.mapper)
        val contracts = PostgresResolutionContractRevisionRepository(jdbc, contractCodec)
        val program = h.f.decoder.decode(h.f.bytes())
        contracts.publish(TenantId(h.tenant), program.pinned.contract)
        val reports = PostgresEvaluationReportRepository(jdbc, transactions, h.f.codec)
        val executed =
            EvaluationExecution(fixtures, h.f.runner, reports)
                .execute(EvaluationCommand(h.fixtureId, h.reportId), h.actor)
                .stored
        flyway(schema).migrate()
        flyway(schema).validate()
        assertThat(reports.find(h.tenant, h.reportId)).isEqualTo(executed)
        val documents = PostgresGateDocumentRepository(jdbc, transactions, h.codec)
        val suites =
            EvaluationSuites(
                documents,
                h.codec,
                fixtures,
                h.f.decoder,
                PublishedEvaluationContractAdapter(contracts, contractCodec, h.f.mapper),
            )
        val policy = suites.register(GateDocumentKind.POLICY, h.policyId, h.policyBytes(), h.actor)
        val first = suites.register(GateDocumentKind.SUITE, h.suiteId, h.suiteBytes(), h.actor)
        val retry = suites.register(GateDocumentKind.SUITE, h.suiteId, h.suiteBytes(), other)
        assertThat(retry.created).isFalse()
        assertThat(retry.stored).isEqualTo(first.stored)
        assertThat(policy.created).isTrue()
        assertThat(documents.find(GateDocumentKind.SUITE, h.suiteId.copy(tenant = UUID.randomUUID()))).isNull()
        assertThatThrownBy { suites.register(GateDocumentKind.POLICY, h.policyId, h.policyBytes(), foreign) }
            .isInstanceOf(GateDocumentUnavailable::class.java)
        val originalPolicy = h.policyBytes().toString(Charsets.UTF_8)
        val changed = originalPolicy.replace("\"requireCostBudget\":false", "\"requireCostBudget\":true")
        assertThatThrownBy { suites.register(GateDocumentKind.POLICY, h.policyId, changed.toByteArray(), other) }
            .isInstanceOf(GateDocumentConflict::class.java)
        val gate = EvaluatePromotionEligibility(suites, h.codec, reports, h.f.codec, fixtures, h.f.packages)
        val selection = SelectedEvaluationReport(h.fixtureRef, h.reportId, executed.reportDigest)
        assertThat(gate.assess(h.suiteId, h.request(selection)).result.eligible).isTrue()
        race(suites, h)
        val badMember = h.member().copy(fixture = h.fixtureRef.copy(sha256 = "0".repeat(64)))
        val badSuite = h.codec.decode(GateDocumentKind.SUITE, h.suiteBytes(listOf(badMember)))
        val badId = h.suiteId.copy(key = "missing-fixture")
        assertThatThrownBy { documents.register(badId, badSuite, h.actor) }
            .isInstanceOf(DataAccessException::class.java)
        assertThat(documents.find(GateDocumentKind.SUITE, badId)).isNull()

        assertThatThrownBy { jdbc.sql("DELETE FROM evaluation_gate_documents").update() }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbc.sql("UPDATE evaluation_suite_fixtures SET fixture_digest = repeat('0', 64)").update()
        }.isInstanceOf(DataAccessException::class.java)
        val invalidSuite = h.codec.decode(GateDocumentKind.SUITE, h.suiteBytes())
        assertThatThrownBy { documents.register(h.suiteId.copy(tenant = UUID.randomUUID()), invalidSuite, h.actor) }
            .isInstanceOf(GateDocumentUnavailable::class.java)
    }

    private fun race(
        suites: EvaluationSuites,
        h: SuiteHarness,
    ) {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1..2).map {
                    pool.submit<Boolean> {
                        check(start.await(10, TimeUnit.SECONDS))
                        val id = h.suiteId.copy(key = "race")
                        suites.register(GateDocumentKind.SUITE, id, h.suiteBytes(), h.actor).created
                    }
                }
            start.countDown()
            assertThat(futures.map { it.get(30, TimeUnit.SECONDS) }.count { it }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `complete empty migration chain validates`() {
        val migration = flyway("empty_suite_" + UUID.randomUUID().toString().replace("-", ""))
        migration.migrate()
        migration.validate()
        assertThat(
            migration
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030180229")
    }

    private fun actor(
        jdbc: JdbcClient,
        tenant: UUID,
        actor: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'suite-test', :subject, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("actor", actor)
            .param("subject", actor.toString())
            .update()
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
