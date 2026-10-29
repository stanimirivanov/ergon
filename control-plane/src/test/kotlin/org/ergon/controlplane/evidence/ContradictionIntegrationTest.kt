package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.evidence.application.AssessedClaimSet
import org.ergon.controlplane.evidence.application.ContradictionConflict
import org.ergon.controlplane.evidence.application.ContradictionSetTooLarge
import org.ergon.controlplane.evidence.application.StoredContradictionAdjudication
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class ContradictionIntegrationTest {
    @Test
    fun `upgrade captures exact accepted set and immutable idempotent decisions retain original proof`() {
        val db = ContradictionDatabase(postgres)
        val assessment = db.assess()
        assertThat(assessment.set.revision).isEqualTo(2)
        assertThat(assessment.findings.single().comparison).isEqualTo(ClaimComparison.CONFLICT)
        val command =
            db.command(assessment).copy(
                choice = ContradictionChoice.DEFER,
                selectedClaimId = null,
            )
        val created = db.repository.register(db.admission, assessment, command)
        assertThat(created.second).isTrue()
        val retry = db.repository.register(db.authority(), assessment, command)
        assertThat(retry.second).isFalse()
        assertThat(retry.first).isEqualTo(created.first)
        assertThat(db.repository.find(TenantId(UUID.randomUUID()), command.id)).isNull()
        assertThatThrownBy { db.repository.register(db.admission, assessment, command.copy(rationale = "Changed")) }
            .isInstanceOf(ContradictionConflict::class.java)
        assertThatThrownBy { db.repository.register(db.admission, assessment, command.copy(id = UUID.randomUUID())) }
            .isInstanceOf(ContradictionConflict::class.java)
        for (sql in listOf(
            "UPDATE evidence_contradiction_adjudications SET rationale = 'Changed'",
            "DELETE FROM evidence_contradiction_adjudications",
        )) {
            assertThatThrownBy { db.jdbc.sql(sql).update() }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(db.repository.currentRevision(db.f.source.tenant, db.f.key)).isEqualTo(2)
    }

    @Test
    fun `new acceptance invalidates stale intent but historical receipt remains recoverable`() {
        val db = ContradictionDatabase(postgres)
        val assessment = db.assess()
        val command = db.command(assessment)
        val original = db.repository.register(db.admission, assessment, command).first
        db.accept(db.add(12))
        assertThat(db.repository.currentRevision(db.f.source.tenant, db.f.key)).isEqualTo(3)
        assertThatThrownBy { db.repository.register(db.admission, assessment, command.copy(id = UUID.randomUUID())) }
            .isInstanceOf(ContradictionConflict::class.java)
        assertThat(db.repository.register(db.admission, assessment, command).first).isEqualTo(original)
        val current = db.assess()
        assertThat(current.set.candidates).hasSize(3)
        assertThat(current.findings).hasSize(3)
        assertThat(db.repository.register(db.admission, current, db.command(current)).second).isTrue()
        assertThatThrownBy {
            db.jdbc
                .sql(
                    """
                    INSERT INTO evidence_contradiction_adjudications
                    SELECT tenant_id, :new_id, anchor_id, property, subject, scope, set_revision, candidate_ids,
                           review_command_ids, finding_codes, algorithm, choice, selected_claim_id, rationale,
                           reviewer_id, authority_evidence_id, adjudicated_at
                    FROM evidence_contradiction_adjudications WHERE command_id = :original
                    """.trimIndent(),
                ).param("new_id", UUID.randomUUID())
                .param("original", command.id)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `pending rejected and differently scoped claims never enter the accepted candidate set`() {
        val db = ContradictionDatabase(postgres)
        db.add(12)
        val rejected = db.add(13)
        db.reviews.decide(
            db.admission,
            EvidenceReviewTarget(EvidenceProposalKind.CLAIM, rejected.id),
            EvidenceReviewCommand(
                UUID.randomUUID(),
                rejected.extraction.id,
                0,
                EvidenceReviewDecision.REJECTED,
                "Rejected",
            ),
        )
        db.accept(db.add(14, "external"))
        val before = db.assess()
        assertThat(before.set.candidates.map { it.stored.proposal.id })
            .containsExactlyElementsOf(db.f.command.candidateIds)
        val accepted = db.f.candidates.first()
        db.reviews.decide(
            db.admission,
            EvidenceReviewTarget(EvidenceProposalKind.CLAIM, accepted.stored.proposal.id),
            EvidenceReviewCommand(
                accepted.reviewCommandId,
                accepted.stored.proposal.extraction.id,
                0,
                EvidenceReviewDecision.ACCEPTED,
                "Checked",
            ),
        )
        assertThat(db.assess()).isEqualTo(before)
        assertThatThrownBy { db.repository.capture(db.f.source.tenant, rejected.id) }
            .isInstanceOf(SourceUnavailable::class.java)
    }

    @Test
    fun `concurrent acceptance and adjudication have a serial outcome and never conceal a changed set`() {
        val db = ContradictionDatabase(postgres)
        val assessment = db.assess()
        val command = db.command(assessment)
        val third = db.add(12)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val acceptance = pool.submit { db.accept(third) }
            val decision =
                pool.submit<Result<Pair<StoredContradictionAdjudication, Boolean>>> {
                    runCatching { db.repository.register(db.admission, assessment, command) }
                }
            acceptance.get(15, TimeUnit.SECONDS)
            val outcome = decision.get(15, TimeUnit.SECONDS)
            if (outcome.isSuccess) {
                assertThat(
                    outcome
                        .getOrThrow()
                        .first.command.expectedRevision,
                ).isEqualTo(2)
            } else {
                assertThat(outcome.exceptionOrNull()).isInstanceOf(ContradictionConflict::class.java)
            }
            assertThat(db.repository.currentRevision(db.f.source.tenant, db.f.key)).isEqualTo(3)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `expired missing and foreign authority fail closed and oversized groups cannot be truncated`() {
        val db = ContradictionDatabase(postgres)
        val assessment = db.assess()
        val command = db.command(assessment)
        for (bad in listOf(
            db.authority(-1),
            db.admission.copy(authority = ApprovalAuthorityEvidenceId(UUID.randomUUID())),
            db.admission.copy(tenant = TenantId(UUID.randomUUID())),
        )) {
            assertThatThrownBy { db.repository.register(bad, assessment, command) }
                .isInstanceOf(SourceUnavailable::class.java)
        }
        for (id in 12L..30L) db.accept(db.add(id))
        assertThatThrownBy { db.assess() }.isInstanceOf(ContradictionSetTooLarge::class.java)
        assertThat(db.repository.find(db.f.source.tenant, command.id)).isNull()
    }

    @Test
    fun `authority expiring during claim set lock waits denies creation and exact recovery`() {
        val db = ContradictionDatabase(postgres)
        val assessment = db.assess()
        val command = db.command(assessment)
        expiredAfterLock(db, assessment, command)
        db.repository.register(db.admission, assessment, command)
        expiredAfterLock(db, assessment, command)
    }

    private fun expiredAfterLock(
        db: ContradictionDatabase,
        assessment: AssessedClaimSet,
        command: ContradictionCommand,
    ) {
        val proof = db.authority(5)
        val pool = Executors.newSingleThreadExecutor()
        try {
            db.data.connection.use { lock ->
                lock.autoCommit = false
                lock.prepareStatement("SELECT revision FROM evidence_claim_set_revisions FOR UPDATE").use {
                    it.executeQuery().close()
                }
                val waiting =
                    pool.submit<Result<Pair<StoredContradictionAdjudication, Boolean>>> {
                        runCatching { db.repository.register(proof, assessment, command) }
                    }
                await().atMost(15, TimeUnit.SECONDS).until {
                    db.jdbc
                        .sql(
                            """
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
                              AND wait_event_type = 'Lock' AND query LIKE '%revision%FOR UPDATE%')
                            AND clock_timestamp() >= (
                              SELECT expires_at FROM approval_authority_evidence WHERE evidence_id = :proof)
                            """.trimIndent(),
                        ).param("proof", proof.authority.value)
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

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
