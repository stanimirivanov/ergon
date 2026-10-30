package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresProcedureProposalRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.ProcedureDefinitionCodec
import org.ergon.controlplane.evidence.application.ProcedureConflict
import org.ergon.controlplane.evidence.application.ProcedureRegistration
import org.ergon.controlplane.knowledge.adapter.out.persistence.PostgresSourceRevisionRepository
import org.ergon.evidence.domain.ProcedureContent
import org.ergon.evidence.domain.ProcedureProposal
import org.ergon.evidence.domain.SourceId
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
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class ProcedureProposalIntegrationTest {
    @Test
    fun `released schema upgrade preserves source attribution and exact procedure retries`() {
        val db = database()
        val proposal = db.f.proposal()
        val created = db.repository.register(proposal, db.f.actor)
        assertThat(created.created).isTrue()
        val retry = db.repository.register(proposal, db.f.actor)
        assertThat(retry.created).isFalse()
        assertThat(retry.stored).isEqualTo(created.stored)
        assertThat(db.repository.find(TenantId(UUID.randomUUID()), proposal.id)).isNull()
        val changed =
            proposal.copy(
                content =
                    ProcedureContent.create(
                        "Changed",
                        proposal.content.citation,
                        proposal.content.steps,
                    ),
            )
        assertThatThrownBy { db.repository.register(changed, db.f.actor) }.isInstanceOf(ProcedureConflict::class.java)
        val plain = db.f.proposal(db.f.command.copy(id = UUID.randomUUID(), operations = emptyList()))
        assertThat(
            db.repository
                .register(plain, db.f.actor)
                .stored.proposal,
        ).isEqualTo(plain)
        for (sql in listOf(
            "UPDATE evidence_procedure_proposals SET title = 'Changed'",
            "DELETE FROM evidence_procedure_proposals",
            "UPDATE evidence_procedure_operation_sources SET step_position = 2",
            "DELETE FROM evidence_procedure_operation_sources",
        )) {
            assertThatThrownBy { db.jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(db.repository.find(db.f.tenant, proposal.id)).isEqualTo(created.stored)
        concurrentRetry(db, proposal.copy(id = UUID.randomUUID()))
    }

    @Test
    fun `failed operation foreign key and incomplete or later links cannot commit partial proposals`() {
        val db = database()
        val proposal = db.f.proposal()
        val first = proposal.content.steps.first()
        val operation = checkNotNull(first.operation)
        val missing = operation.citation.revision.copy(sourceId = SourceId(UUID.randomUUID()))
        val invalidOperation =
            operation.copy(
                extraction = operation.extraction.copy(source = missing),
                citation = operation.citation.copy(revision = missing),
            )
        val invalid =
            proposal.copy(
                content =
                    ProcedureContent.create(
                        proposal.content.title,
                        proposal.content.citation,
                        listOf(first.copy(operation = invalidOperation), proposal.content.steps.last()),
                    ),
            )
        assertThatThrownBy { db.repository.register(invalid, db.f.actor) }.isInstanceOf(DataAccessException::class.java)
        assertThat(db.repository.find(db.f.tenant, invalid.id)).isNull()
        assertThatThrownBy { insertWithoutLinks(db, proposal) }.isInstanceOf(DataAccessException::class.java)
        assertThat(db.repository.find(db.f.tenant, proposal.id)).isNull()
        val plain = db.f.proposal(db.f.command.copy(operations = emptyList()))
        db.repository.register(plain, db.f.actor)
        assertThatThrownBy {
            db.jdbc
                .sql(
                    """
                    INSERT INTO evidence_procedure_operation_sources
                        (tenant_id, proposal_id, step_position, source_id, digest)
                    VALUES (:tenant, :id, 1, :source, :digest)
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to db.f.tenant.value,
                        "id" to plain.id,
                        "source" to db.f.api.revision.sourceId.value,
                        "digest" to db.f.api.revision.digest.value,
                    ),
                ).update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(db.repository.find(db.f.tenant, plain.id)?.proposal).isEqualTo(plain)
    }

    @Test
    fun `complete migration chain validates against a fresh schema`() {
        val current = flyway("procedure_empty_" + UUID.randomUUID().toString().replace("-", ""), "latest")
        current.migrate()
        current.validate()
        assertThat(
            current
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030195306")
    }

    private fun database(): Database {
        val schema = "procedure_" + UUID.randomUUID().toString().replace("-", "")
        flyway(schema, "20261029170000").migrate()
        val separator = if (postgres.jdbcUrl.contains('?')) "&" else "?"
        val data =
            DriverManagerDataSource(
                "${postgres.jdbcUrl}${separator}currentSchema=$schema",
                postgres.username,
                postgres.password,
            )
        val jdbc = JdbcClient.create(data)
        assertThat(jdbc.sql("SELECT current_schema()").query(String::class.java).single()).isEqualTo(schema)
        val f = ProcedureFixtures()
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'test', 'subject', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", f.tenant.value)
            .param("actor", f.actor.value)
            .update()
        val transactions = TransactionTemplate(DataSourceTransactionManager(data))
        val sources = PostgresSourceRevisionRepository(jdbc, transactions)
        sources.register(f.markdown, f.actor)
        sources.register(f.api, f.actor)
        val current = flyway(schema, "latest")
        current.migrate()
        current.validate()
        val codec = ProcedureDefinitionCodec(JsonMapper.builder().build())
        return Database(jdbc, PostgresProcedureProposalRepository(jdbc, transactions, codec), codec, f)
    }

    private fun insertWithoutLinks(
        db: Database,
        proposal: ProcedureProposal,
    ) {
        db.jdbc
            .sql(
                """
                INSERT INTO evidence_procedure_proposals
                    (tenant_id, proposal_id, source_id, digest, extraction_id, compiler_revision,
                     observation_index, title, step_count, definition, proposed_by)
                VALUES (:tenant, :id, :source, :digest, :extraction, :compiler,
                        :index, :title, :count, CAST(:definition AS JSONB), :actor)
                """.trimIndent(),
            ).params(
                mapOf(
                    "tenant" to db.f.tenant.value,
                    "id" to proposal.id,
                    "source" to db.f.markdown.revision.sourceId.value,
                    "digest" to db.f.markdown.revision.digest.value,
                    "extraction" to proposal.extraction.id,
                    "compiler" to proposal.extraction.extractorRevision,
                    "index" to proposal.observationIndex,
                    "title" to proposal.content.title,
                    "count" to proposal.content.steps.size,
                    "definition" to db.codec.encode(proposal.content),
                    "actor" to db.f.actor.value,
                ),
            ).update()
    }

    private fun concurrentRetry(
        db: Database,
        proposal: ProcedureProposal,
    ) {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit<ProcedureRegistration> { db.repository.register(proposal, db.f.actor) }
                }
            val outcomes = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(outcomes.count { it.created }).isEqualTo(1)
            assertThat(outcomes.map { it.stored }.distinct()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }

    private data class Database(
        val jdbc: JdbcClient,
        val repository: PostgresProcedureProposalRepository,
        val codec: ProcedureDefinitionCodec,
        val f: ProcedureFixtures,
    )

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))

        private fun flyway(
            schema: String,
            target: String,
        ): Flyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load()
    }
}
