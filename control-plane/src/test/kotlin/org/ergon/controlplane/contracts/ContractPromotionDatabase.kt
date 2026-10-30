package org.ergon.controlplane.contracts

import org.ergon.controlplane.cases.adapter.out.transaction.SpringTransactionRunner
import org.ergon.controlplane.contracts.adapter.inbound.document.PromotionDocumentCodec
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresContractPromotionRepository
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresPromotionAuthorityRepository
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.adapter.out.persistence.ResolutionContractJsonCodec
import org.ergon.controlplane.contracts.application.ContractPromotionIntent
import org.ergon.controlplane.contracts.application.ContractPromotions
import org.ergon.controlplane.contracts.application.PromotionAuthorityIntent
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.ergon.controlplane.evaluation.SuiteHarness
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresEvaluationReportRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresGateDocumentRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationFixtureRepository
import org.ergon.controlplane.evaluation.adapter.out.persistence.PublishedEvaluationContractAdapter
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationExecution
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.identity.domain.TenantId
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.OffsetDateTime
import java.util.UUID

/** Seed the released PR9 reference graph before upgrading; promotions never replace that graph. */
internal class ContractPromotionDatabase(
    private val postgres: PostgreSQLContainer,
    private val upgradeFrom: String = "20261030171119",
) {
    val schema = "promotion_" + UUID.randomUUID().toString().replace("-", "")
    val h = SuiteHarness()
    val tenant = h.tenant
    val actor = h.actor
    val key = h.fixture.fixture.contract.key
    private val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
    private val data =
        DriverManagerDataSource(
            postgres.jdbcUrl + "${separator}currentSchema=$schema",
            postgres.username,
            postgres.password,
        )
    val jdbc = JdbcClient.create(data)
    val transactions = TransactionTemplate(DataSourceTransactionManager(data))
    val runner = SpringTransactionRunner(transactions)
    val codec = PromotionDocumentCodec(h.f.pinned, h.f.mapper)
    val repository = PostgresContractPromotionRepository(jdbc, codec)
    val authorities = PostgresPromotionAuthorityRepository(jdbc, transactions)
    val suites: EvaluationSuites
    val service: ContractPromotions
    val suite: org.ergon.evaluation.domain.SimulationArtifact

    init {
        migration(upgradeFrom).migrate()
        actor(tenant, actor)
        val fixtures = PostgresSimulationFixtureRepository(jdbc, transactions, h.f.packages)
        fixtures.register(h.fixtureId, h.fixture, actor)
        val contractsCodec = ResolutionContractJsonCodec(h.f.mapper)
        val contracts = PostgresResolutionContractRevisionRepository(jdbc, contractsCodec)
        contracts.publish(
            TenantId(tenant),
            h.f.decoder
                .decode(h.f.bytes())
                .pinned.contract,
        )
        val reports = PostgresEvaluationReportRepository(jdbc, transactions, h.f.codec)
        EvaluationExecution(fixtures, h.f.runner, reports).execute(EvaluationCommand(h.fixtureId, h.reportId), actor)
        val documents = PostgresGateDocumentRepository(jdbc, transactions, h.codec)
        suites =
            EvaluationSuites(
                documents,
                h.codec,
                fixtures,
                h.f.decoder,
                PublishedEvaluationContractAdapter(contracts, contractsCodec, h.f.mapper),
            )
        suites.register(GateDocumentKind.POLICY, h.policyId, h.policyBytes(), actor)
        suite = suites.register(GateDocumentKind.SUITE, h.suiteId, h.suiteBytes(), actor).stored.reference()
        migration().migrate()
        migration().validate()
        service =
            ContractPromotions(
                runner,
                repository,
                authorities,
                suites,
                h.codec,
                EvaluatePromotionEligibility(suites, h.codec, reports, h.f.codec, fixtures, h.f.packages),
            )
    }

    fun actor(
        tenant: UUID,
        actor: UUID = UUID.randomUUID(),
    ): UUID {
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'promotion-test', :subject, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("actor", actor)
            .param("subject", actor.toString())
            .update()
        return actor
    }

    fun now(): OffsetDateTime = jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime::class.java).single()

    fun grant(
        actor: UUID = this.actor,
        seconds: Long = 3600,
    ) = authorities
        .attest(
            tenant,
            UUID.randomUUID(),
            PromotionAuthorityIntent(actor, "test", "grant", now().plusSeconds(seconds).toInstant()),
        ).authority

    fun bind() =
        service.bind(
            tenant,
            key,
            UUID.randomUUID(),
            actor,
            RequiredSuiteIntent(0, suite, "Required suite reviewed"),
        )

    fun intent() =
        ContractPromotionIntent(
            1,
            suite,
            h.fixture.fixture.contract,
            h.policyRef(),
            listOf(h.selection()),
            "Promotion reviewed",
        )

    fun migration(target: String = "latest"): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema)
            .defaultSchema(schema)
            .target(target)
            .load()
}
