package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresClaimProposalRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresContradictionRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresEvidenceReviewRepository
import org.ergon.controlplane.evidence.application.AssessedClaimSet
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.knowledge.adapter.out.persistence.PostgresSourceRevisionRepository
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.DocumentaryContradictions
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

/** Each test exercises an upgrade with two previously accepted claims, isolated by a unique PostgreSQL schema. */
internal class ContradictionDatabase(
    postgres: PostgreSQLContainer,
) {
    val f = ContradictionFixtures()
    val schema = "contradiction_" + UUID.randomUUID().toString().replace("-", "")
    private val migrations =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema)
            .defaultSchema(schema)
    val data =
        DriverManagerDataSource(
            postgres.jdbcUrl + (if (postgres.jdbcUrl.contains('?')) "&" else "?") + "currentSchema=$schema",
            postgres.username,
            postgres.password,
        )
    val jdbc = JdbcClient.create(data)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(data))
    val claims = PostgresClaimProposalRepository(jdbc, transactions)
    val reviews = PostgresEvidenceReviewRepository(jdbc, transactions)
    val repository = PostgresContradictionRepository(jdbc, transactions, claims)
    val admission: EvidenceReviewAdmission

    init {
        migrations.target("20261029200000").load().migrate()
        check(jdbc.sql("SELECT current_schema()").query(String::class.java).single() == schema)
        jdbc
            .sql(
                """
                INSERT INTO human_actors (tenant_id, actor_id, identity_provider, identity_subject, registered_at)
                VALUES (:tenant, :actor, 'test', 'subject', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).param("tenant", f.source.tenant.value)
            .param("actor", f.source.actor.value)
            .update()
        PostgresSourceRevisionRepository(jdbc, transactions).register(f.source.markdown, f.source.actor)
        admission = authority()
        for (candidate in f.candidates) {
            val proposal = candidate.stored.proposal
            claims.register(proposal, f.source.actor)
            accept(proposal, candidate.reviewCommandId)
        }
        val current = migrations.target("latest").load()
        current.migrate()
        current.validate()
    }

    fun authority(seconds: Int = 600): EvidenceReviewAdmission {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO approval_authority_evidence
                  (tenant_id, evidence_id, actor_id, authority, source_provider, source_reference,
                    attested_at, expires_at)
                VALUES (:tenant, :id, :actor, 'RESOLVER', 'test', 'resolvers',
                        clock_timestamp() - INTERVAL '60 seconds', clock_timestamp() + :seconds * INTERVAL '1 second')
                """.trimIndent(),
            ).param("tenant", f.source.tenant.value)
            .param("id", id)
            .param("actor", f.source.actor.value)
            .param("seconds", seconds)
            .update()
        return EvidenceReviewAdmission(f.source.tenant, f.source.actor, ApprovalAuthorityEvidenceId(id))
    }

    fun add(
        id: Long,
        scope: String = f.key.scope,
    ): ClaimProposal {
        val proposal = f.candidate(id, true).stored.proposal
        val scoped = proposal.copy(claim = proposal.claim.copy(scope = scope))
        return claims.register(scoped, f.source.actor).stored.proposal
    }

    fun accept(
        proposal: ClaimProposal,
        command: UUID = UUID.randomUUID(),
    ) {
        reviews.decide(
            admission,
            EvidenceReviewTarget(EvidenceProposalKind.CLAIM, proposal.id),
            EvidenceReviewCommand(command, proposal.extraction.id, 0, EvidenceReviewDecision.ACCEPTED, "Checked"),
        )
    }

    fun assess(): AssessedClaimSet {
        val set = repository.capture(f.source.tenant, f.command.anchorId)
        return AssessedClaimSet(set, DocumentaryContradictions.assess(set.candidates.map { it.stored.proposal }))
    }

    fun command(assessment: AssessedClaimSet = assess()): ContradictionCommand =
        f.command.copy(
            id = UUID.randomUUID(),
            expectedRevision = assessment.set.revision,
            candidateIds = assessment.set.candidates.map { it.stored.proposal.id },
        )
}
