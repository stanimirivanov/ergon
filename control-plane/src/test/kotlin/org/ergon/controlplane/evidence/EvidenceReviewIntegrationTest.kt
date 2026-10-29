package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresClaimProposalRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresEvidenceReviewRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresProcedureProposalRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.ProcedureDefinitionCodec
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.evidence.application.EvidenceReviewConflict
import org.ergon.controlplane.evidence.application.EvidenceReviewCursor
import org.ergon.controlplane.evidence.application.EvidenceReviewRegistration
import org.ergon.controlplane.evidence.application.PendingEvidenceProposal
import org.ergon.controlplane.knowledge.adapter.out.persistence.PostgresSourceRevisionRepository
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
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
class EvidenceReviewIntegrationTest {
    @Test
    fun `upgrading preserves pending targets and terminal decisions recover exactly with renewed authority`() {
        val db = database()
        val admission = admission(db)
        val pending = db.reviews.pending(db.f.tenant, null, 20)
        assertThat(pending.map { it.target.kind })
            .containsExactlyInAnyOrder(EvidenceProposalKind.CLAIM, EvidenceProposalKind.PROCEDURE)
        for (item in pending) {
            val command = command(item)
            val created = db.reviews.decide(admission, item.target, command)
            assertThat(created.created).isTrue()
            assertThat(db.reviews.decide(admission(db), item.target, command).receipt).isEqualTo(created.receipt)
            assertThat(db.reviews.decide(admission, item.target, command).created).isFalse()
            assertThatThrownBy { db.reviews.decide(admission, item.target, command.copy(rationale = "Changed")) }
                .isInstanceOf(EvidenceReviewConflict::class.java)
            assertThatThrownBy { db.reviews.decide(admission, item.target, command.copy(id = UUID.randomUUID())) }
                .isInstanceOf(EvidenceReviewConflict::class.java)
            assertThat(db.reviews.find(TenantId(UUID.randomUUID()), item.target)).isNull()
        }
        assertThat(db.reviews.pending(db.f.tenant, null, 20)).isEmpty()
        for (sql in listOf(
            "UPDATE evidence_proposal_reviews SET rationale = 'Changed'",
            "DELETE FROM evidence_proposal_reviews",
        )) {
            assertThatThrownBy { db.jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(db.procedures.find(db.f.tenant, db.f.command.id)?.proposal).isEqualTo(db.f.proposal())
    }

    @Test
    fun `stale extraction version expired and foreign proof cannot write and infrastructure failures propagate`() {
        val db = database()
        val item = db.reviews.pending(db.f.tenant, null, 1).single()
        val command = command(item)
        val admission = admission(db)
        for (bad in listOf(command.copy(extractionId = UUID.randomUUID()), command.copy(expectedVersion = 1))) {
            assertThatThrownBy { db.reviews.decide(admission, item.target, bad) }
                .isInstanceOf(EvidenceReviewConflict::class.java)
        }
        for (bad in listOf(
            admission.copy(authority = ApprovalAuthorityEvidenceId(UUID.randomUUID())),
            admission.copy(tenant = TenantId(UUID.randomUUID())),
            admission(db, -1),
        )) {
            assertThatThrownBy { db.reviews.decide(bad, item.target, command) }
                .isInstanceOf(SourceUnavailable::class.java)
        }
        assertThat(db.reviews.find(db.f.tenant, item.target)).isNull()
        db.jdbc.sql("ALTER TABLE evidence_proposal_reviews RENAME TO unavailable_reviews").update()
        assertThatThrownBy { db.reviews.find(db.f.tenant, item.target) }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `competing decisions serialize and tenant command IDs cannot be reused across kinds`() {
        val db = database()
        val admission = admission(db)
        val items = db.reviews.pending(db.f.tenant, null, 20)
        val item = items.first()
        val first = command(item)
        val second = first.copy(id = UUID.randomUUID(), decision = EvidenceReviewDecision.REJECTED)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(first, second)
                    .map { command ->
                        pool.submit<Result<EvidenceReviewRegistration>> {
                            runCatching { db.reviews.decide(admission, item.target, command) }
                        }
                    }.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.single { it.isFailure }.exceptionOrNull())
                .isInstanceOf(EvidenceReviewConflict::class.java)
            val winner =
                results
                    .single { it.isSuccess }
                    .getOrThrow()
                    .receipt.command
            val other = items.last()
            assertThatThrownBy {
                db.reviews.decide(admission, other.target, command(other).copy(id = winner.id))
            }.isInstanceOf(EvidenceReviewConflict::class.java)
            val retryCommand = command(other)
            val retries =
                (1..2)
                    .map {
                        pool.submit<EvidenceReviewRegistration> {
                            db.reviews.decide(admission, other.target, retryCommand)
                        }
                    }.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(retries.count { it.created }).isEqualTo(1)
            assertThat(retries.map { it.receipt }.distinct()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `pending cursor handles exact time ties across kinds without duplicates or tenant leakage`() {
        val db = database()
        // Fixture-only triggers are disabled to establish deterministic queue timestamps.
        for (table in listOf("evidence_claim_proposals", "evidence_procedure_proposals")) {
            db.jdbc.sql("ALTER TABLE $table DISABLE TRIGGER USER").update()
            db.jdbc.sql("UPDATE $table SET recorded_at = TIMESTAMPTZ '2026-10-29 12:00:00+00'").update()
            db.jdbc.sql("ALTER TABLE $table ENABLE TRIGGER USER").update()
        }
        val first = db.reviews.pending(db.f.tenant, null, 1).single()
        assertThat(first.target.kind).isEqualTo(EvidenceProposalKind.PROCEDURE)
        val cursor = EvidenceReviewCursor(first.recordedAt, first.target.kind, first.target.id)
        val last = db.reviews.pending(db.f.tenant, cursor, 20).single()
        assertThat(last.target.kind).isEqualTo(EvidenceProposalKind.CLAIM)
        val end = EvidenceReviewCursor(last.recordedAt, last.target.kind, last.target.id)
        assertThat(db.reviews.pending(db.f.tenant, end, 20))
            .isEmpty()
        assertThat(db.reviews.pending(TenantId(UUID.randomUUID()), null, 20)).isEmpty()
    }

    @Test
    fun `authority expiring while waiting for proposal lock cannot create or recover a receipt`() {
        val db = database()
        val item = db.reviews.pending(db.f.tenant, null, 1).single()
        val command = command(item)
        db.reviews.decide(admission(db), item.target, command)
        assertExpiredAfterLock(db, item, command)
        val pending = db.reviews.pending(db.f.tenant, null, 1).single()
        assertExpiredAfterLock(db, pending, command(pending))
        assertThat(db.reviews.find(db.f.tenant, pending.target)).isNull()
    }

    private fun assertExpiredAfterLock(
        db: Database,
        item: PendingEvidenceProposal,
        selected: EvidenceReviewCommand,
    ) {
        val short = admission(db, 5)
        val pool = Executors.newSingleThreadExecutor()
        try {
            db.data.connection.use { lock ->
                lock.autoCommit = false
                val table =
                    if (item.target.kind == EvidenceProposalKind.CLAIM) {
                        "evidence_claim_proposals"
                    } else {
                        "evidence_procedure_proposals"
                    }
                val sql = "SELECT 1 FROM $table WHERE tenant_id = ? AND proposal_id = ? FOR UPDATE"
                lock.prepareStatement(sql).use {
                    it.setObject(1, db.f.tenant.value)
                    it.setObject(2, item.target.id)
                    it.executeQuery().close()
                }
                val waiting =
                    pool.submit<Result<EvidenceReviewRegistration>> {
                        runCatching { db.reviews.decide(short, item.target, selected) }
                    }
                await().atMost(5, TimeUnit.SECONDS).until {
                    db.jdbc
                        .sql(
                            """
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
                              AND wait_event_type = 'Lock' AND query LIKE '%extraction_id%FOR UPDATE%')
                            """.trimIndent(),
                        ).query(Boolean::class.java)
                        .single()
                }
                await().atMost(15, TimeUnit.SECONDS).until {
                    db.jdbc
                        .sql(
                            """
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
                              AND wait_event_type = 'Lock' AND query LIKE '%extraction_id%FOR UPDATE%')
                            AND clock_timestamp() >= (
                              SELECT expires_at FROM approval_authority_evidence WHERE evidence_id = :proof)
                            """.trimIndent(),
                        ).param("proof", short.authority.value)
                        .query(Boolean::class.java)
                        .single()
                }
                lock.commit()
                assertThat(waiting.get(10, TimeUnit.SECONDS).exceptionOrNull())
                    .isInstanceOf(SourceUnavailable::class.java)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `direct SQL cannot bypass extraction authority or tenant identity admission`() {
        val db = database()
        val item = db.reviews.pending(db.f.tenant, null, 1).single()
        val current = admission(db)
        assertThatThrownBy { directInsert(db, item, current, UUID.randomUUID()) }
            .isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { directInsert(db, item, admission(db, -1), item.extraction.id) }
            .isInstanceOf(DataAccessException::class.java)
        val foreign = current.copy(tenant = TenantId(UUID.randomUUID()))
        assertThatThrownBy { directInsert(db, item, foreign, item.extraction.id) }
            .isInstanceOf(DataAccessException::class.java)
        val unknown = current.copy(authority = ApprovalAuthorityEvidenceId(UUID.randomUUID()))
        assertThatThrownBy { directInsert(db, item, unknown, item.extraction.id) }
            .isInstanceOf(DataAccessException::class.java)
        assertThat(db.reviews.find(db.f.tenant, item.target)).isNull()
        directInsert(db, item, current, item.extraction.id)
        assertThat(
            db.reviews
                .find(db.f.tenant, item.target)
                ?.recordedAt
                ?.isAfter(java.time.Instant.EPOCH),
        ).isTrue()
    }

    private fun directInsert(
        db: Database,
        item: PendingEvidenceProposal,
        admission: EvidenceReviewAdmission,
        extraction: UUID,
    ) {
        db.jdbc
            .sql(
                """
                INSERT INTO evidence_proposal_reviews
                  (tenant_id, proposal_kind, proposal_id, claim_id, procedure_id, command_id, extraction_id,
                   decision, rationale, reviewer_id, authority_evidence_id, reviewed_at)
                VALUES (:tenant, :kind, :id, :claim, :procedure, :command, :extraction,
                        'REJECTED', 'Reviewed directly', :actor, :authority, TIMESTAMPTZ '1970-01-01 00:00:00+00')
                """.trimIndent(),
            ).params(
                mapOf(
                    "tenant" to admission.tenant.value,
                    "kind" to item.target.kind.name,
                    "id" to item.target.id,
                    "claim" to item.target.id.takeIf { item.target.kind == EvidenceProposalKind.CLAIM },
                    "procedure" to item.target.id.takeIf { item.target.kind == EvidenceProposalKind.PROCEDURE },
                    "command" to UUID.randomUUID(),
                    "extraction" to extraction,
                    "actor" to admission.reviewer.value,
                    "authority" to admission.authority.value,
                ),
            ).update()
    }

    private fun command(item: PendingEvidenceProposal) =
        EvidenceReviewCommand(
            UUID.randomUUID(),
            item.extraction.id,
            0,
            EvidenceReviewDecision.ACCEPTED,
            "Checked exact source",
        )

    private fun admission(
        db: Database,
        seconds: Int = 600,
    ): EvidenceReviewAdmission {
        val id = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        db.jdbc
            .sql(
                """
                INSERT INTO approval_authority_evidence
                  (tenant_id, evidence_id, actor_id, authority, source_provider, source_reference,
                   attested_at, expires_at)
                VALUES (:tenant, :id, :actor, 'RESOLVER', 'test', 'resolvers',
                        clock_timestamp() - INTERVAL '60 seconds', clock_timestamp() + :seconds * INTERVAL '1 second')
                """.trimIndent(),
            ).param("tenant", db.f.tenant.value)
            .param("id", id.value)
            .param("actor", db.f.actor.value)
            .param("seconds", seconds)
            .update()
        return EvidenceReviewAdmission(db.f.tenant, db.f.actor, id)
    }

    private fun database(): Database {
        val schema = "review_" + UUID.randomUUID().toString().replace("-", "")
        val previous = flyway(schema, "20261029190000")
        previous.migrate()
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
        val codec = ProcedureDefinitionCodec(JsonMapper.builder().build())
        val procedures = PostgresProcedureProposalRepository(jdbc, transactions, codec)
        procedures.register(f.proposal(), f.actor)
        val claim =
            ClaimProposal(
                f.command.id,
                f.compiled.extraction,
                0,
                f.compiled.observations[0].citation,
                DocumentaryClaim(DocumentaryProperty.SUPPORT, DocumentaryValue.Support(true), "recovery", "staff"),
            )
        PostgresClaimProposalRepository(jdbc, transactions).register(claim, f.actor)
        val current = flyway(schema, "latest")
        current.migrate()
        current.validate()
        return Database(data, jdbc, PostgresEvidenceReviewRepository(jdbc, transactions), procedures, f)
    }

    private data class Database(
        val data: DriverManagerDataSource,
        val jdbc: JdbcClient,
        val reviews: PostgresEvidenceReviewRepository,
        val procedures: PostgresProcedureProposalRepository,
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
