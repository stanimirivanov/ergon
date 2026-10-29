package org.ergon.controlplane.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.ergon.controlplane.evidence.adapter.serialization.bundleDigest
import org.ergon.controlplane.evidence.application.EvidenceBundleConflict
import org.ergon.controlplane.evidence.application.EvidenceBundleRegistration
import org.ergon.controlplane.evidence.application.InvalidEvidenceBundle
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.BundleClaimSet
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.BundleProposalReference
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.DocumentaryClaimKey
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceBundleManifest
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class EvidenceBundleIntegrationTest {
    @Test
    fun `publication pins complete immutable references and exact retries retain cutoff publisher and digest`() {
        val b = EvidenceBundleDatabase(postgres)
        val created = b.repository.publish(b.db.admission, b.command, b.content)
        assertThat(created.created).isTrue()
        assertThat(created.stored.manifest.content).isEqualTo(b.content)
        assertThat(created.stored.sha256)
            .isEqualTo(bundleDigest(b.codec.encode(b.f.source.tenant, created.stored.manifest)))
        assertThat(b.repository.publish(b.db.authority(), b.command, b.content).stored).isEqualTo(created.stored)
        assertThat(b.repository.find(TenantId(UUID.randomUUID()), b.command.id)).isNull()
        assertThatThrownBy { b.repository.publish(b.db.admission, b.command.copy(procedures = emptyList()), b.content) }
            .isInstanceOf(EvidenceBundleConflict::class.java)
        for (table in listOf(
            "evidence_bundles",
            "evidence_bundle_proposals",
            "evidence_bundle_sources",
            "evidence_bundle_adjudications",
        )) {
            assertThatThrownBy {
                b.db.jdbc
                    .sql("DELETE FROM $table")
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
        assertThat(b.repository.find(b.f.source.tenant, b.command.id)).isEqualTo(created.stored)
    }

    @Test
    fun `new acceptance invalidates new stale publication but never reevaluates an existing manifest`() {
        val b = EvidenceBundleDatabase(postgres)
        val original = b.repository.publish(b.db.admission, b.command, b.content).stored
        b.db.accept(b.db.add(12))
        assertThatThrownBy { b.repository.publish(b.db.admission, b.command.copy(id = UUID.randomUUID()), b.content) }
            .isInstanceOf(EvidenceBundleConflict::class.java)
        assertThat(b.repository.find(b.f.source.tenant, b.command.id)).isEqualTo(original)
        assertThat(b.repository.publish(b.db.admission, b.command, b.content).stored).isEqualTo(original)
        val assessment = b.db.assess()
        val decision =
            b.db.repository
                .register(b.db.admission, assessment, b.db.command(assessment))
                .first
        val current = b.prepare(assessment, decision)
        val command =
            b.command.copy(
                id = UUID.randomUUID(),
                claimSets =
                    listOf(
                        BundleClaimSetSelection(
                            b.command.claims.single(),
                            assessment.set.revision,
                            assessment.set.candidates.map { it.stored.proposal.id },
                            decision.command.id,
                        ),
                    ),
            )
        assertThat(b.repository.publish(b.db.admission, command, current).created).isTrue()
    }

    @Test
    fun `SQL admission rejects forged findings pending procedures and missing or expired authority`() {
        val b = EvidenceBundleDatabase(postgres, acceptProcedure = false)
        assertThatThrownBy { b.repository.publish(b.db.admission, b.command, b.content) }
            .isInstanceOf(EvidenceBundleConflict::class.java)
        val accepted = EvidenceBundleDatabase(postgres)
        val set = accepted.content.claimSets.single()
        val forged =
            accepted.content.copy(
                claimSets = listOf(set.copy(findingCodes = listOf(ClaimComparison.COMPATIBLE), adjudicationId = null)),
            )
        val command =
            accepted.command.copy(
                claimSets = accepted.command.claimSets.map { it.copy(adjudicationId = null) },
            )
        assertThatThrownBy { accepted.repository.publish(accepted.db.admission, command, forged) }
            .isInstanceOf(InvalidEvidenceBundle::class.java)
        for (bad in listOf(
            accepted.db.authority(-1),
            accepted.db.admission.copy(authority = ApprovalAuthorityEvidenceId(UUID.randomUUID())),
            accepted.db.admission.copy(tenant = TenantId(UUID.randomUUID())),
        )) {
            assertThatThrownBy { accepted.repository.publish(bad, accepted.command, accepted.content) }
                .isInstanceOf(SourceUnavailable::class.java)
        }
        assertThat(accepted.repository.find(accepted.f.source.tenant, accepted.command.id)).isNull()
    }

    @Test
    fun `singleton unknown validity cannot bypass qualification through direct persistence`() {
        val b = EvidenceBundleDatabase(postgres)
        val draft =
            b.f
                .candidate(99, true)
                .stored.proposal
        val unknown = draft.copy(claim = draft.claim.copy(scope = "unknown", validFrom = null, validUntil = null))
        b.db.claims.register(unknown, b.f.source.actor)
        b.db.accept(unknown)
        val target = EvidenceReviewTarget(EvidenceProposalKind.CLAIM, unknown.id)
        val review = checkNotNull(b.db.reviews.find(b.f.source.tenant, target))
        val set =
            BundleClaimSet(
                DocumentaryClaimKey.of(unknown.claim),
                1,
                listOf(unknown.id),
                listOf(review.command.id),
                emptyList(),
                null,
            )
        val content =
            EvidenceBundleContent(
                listOf(BundleProposalReference(target, unknown.extraction, review.command.id, true)),
                listOf(unknown.extraction.source),
                listOf(set),
            )
        val command =
            EvidenceBundleCommand(
                UUID.randomUUID(),
                listOf(unknown.id),
                emptyList(),
                listOf(BundleClaimSetSelection(unknown.id, 1, listOf(unknown.id), null)),
            )
        assertThatThrownBy { b.repository.publish(b.db.admission, command, content) }
            .isInstanceOf(InvalidEvidenceBundle::class.java)
        assertThat(b.repository.find(b.f.source.tenant, command.id)).isNull()
    }

    @Test
    fun `incomplete or later foreign key ledgers cannot commit a partial manifest`() {
        val b = EvidenceBundleDatabase(postgres)
        val cutoff =
            b.db.jdbc
                .sql("SELECT clock_timestamp()")
                .query(OffsetDateTime::class.java)
                .single()
        val text = b.codec.encode(b.f.source.tenant, EvidenceBundleManifest(cutoff.toInstant(), b.content))
        assertThatThrownBy {
            b.db.jdbc
                .sql(
                    """
                    INSERT INTO evidence_bundles
                      (tenant_id, bundle_id, publication_command, canonical_manifest, manifest_sha256,
                       assessed_at, published_by, authority_evidence_id)
                    VALUES (:tenant, :id, :command, :manifest, :digest, :cutoff, :actor, :authority)
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to b.f.source.tenant.value,
                        "id" to b.command.id,
                        "command" to b.codec.encodeCommand(b.command),
                        "manifest" to text,
                        "digest" to bundleDigest(text),
                        "cutoff" to cutoff,
                        "actor" to b.f.source.actor.value,
                        "authority" to b.db.admission.authority.value,
                    ),
                ).update()
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(b.repository.find(b.f.source.tenant, b.command.id)).isNull()
        b.repository.publish(b.db.admission, b.command, b.content)
        val other = b.db.add(12)
        b.db.accept(other)
        val review =
            checkNotNull(
                b.db.reviews.find(
                    b.f.source.tenant,
                    EvidenceReviewTarget(EvidenceProposalKind.CLAIM, other.id),
                ),
            )
        assertThatThrownBy {
            b.db.jdbc
                .sql(
                    """
                    INSERT INTO evidence_bundle_proposals
                      (tenant_id, bundle_id, kind, proposal_id, claim_id, review_command_id)
                    VALUES (:tenant, :bundle, 'CLAIM', :id, :id, :review)
                    """.trimIndent(),
                ).param("tenant", b.f.source.tenant.value)
                .param("bundle", b.command.id)
                .param("id", other.id)
                .param("review", review.command.id)
                .update()
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `procedure only concurrent first publication has one immutable receipt`() {
        val b = EvidenceBundleDatabase(postgres)
        val content =
            b.content.copy(
                proposals =
                    b.content.proposals.filter {
                        it.target.kind == EvidenceProposalKind.PROCEDURE
                    },
                claimSets = emptyList(),
            )
        val command = b.command.copy(claims = emptyList(), claimSets = emptyList())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2)
                    .map {
                        pool.submit<EvidenceBundleRegistration> {
                            b.repository.publish(
                                b.db.admission,
                                command,
                                content,
                            )
                        }
                    }.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(results.map { it.stored }.distinct()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent acceptance and publication have a serial cutoff without hidden candidate changes`() {
        val b = EvidenceBundleDatabase(postgres)
        val next = b.db.add(12)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val acceptance = pool.submit { b.db.accept(next) }
            val publication =
                pool.submit<Result<EvidenceBundleRegistration>> {
                    runCatching { b.repository.publish(b.db.admission, b.command, b.content) }
                }
            acceptance.get(15, TimeUnit.SECONDS)
            val result = publication.get(15, TimeUnit.SECONDS)
            if (result.isSuccess) {
                assertThat(
                    result
                        .getOrThrow()
                        .stored.manifest.content.claimSets
                        .single()
                        .revision,
                ).isEqualTo(2)
            } else {
                assertThat(result.exceptionOrNull()).isInstanceOf(EvidenceBundleConflict::class.java)
            }
            assertThat(b.db.repository.currentRevision(b.f.source.tenant, b.f.key)).isEqualTo(3)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `authority expiring during publication identity waits denies receipt recovery`() {
        val b = EvidenceBundleDatabase(postgres)
        b.repository.publish(b.db.admission, b.command, b.content)
        val proof = b.db.authority(5)
        val pool = Executors.newSingleThreadExecutor()
        try {
            b.db.data.connection.use { lock ->
                lock.autoCommit = false
                lock.prepareStatement("SELECT bundle_id FROM evidence_bundle_publication_keys FOR UPDATE").use {
                    it.executeQuery().close()
                }
                val pending =
                    pool.submit<Result<EvidenceBundleRegistration>> {
                        runCatching { b.repository.publish(proof, b.command, b.content) }
                    }
                await().atMost(15, TimeUnit.SECONDS).until {
                    b.db.jdbc
                        .sql(
                            """
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database()
                              AND wait_event_type = 'Lock' AND query LIKE '%bundle_id%FOR UPDATE%')
                            AND clock_timestamp() >= (
                              SELECT expires_at FROM approval_authority_evidence WHERE evidence_id = :proof)
                            """.trimIndent(),
                        ).param("proof", proof.authority.value)
                        .query(Boolean::class.java)
                        .single()
                }
                lock.commit()
                assertThat(pending.get(10, TimeUnit.SECONDS).exceptionOrNull())
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
