package org.ergon.controlplane.evidence.adapter.out.persistence

import org.ergon.controlplane.evidence.adapter.serialization.EvidenceBundleCodec
import org.ergon.controlplane.evidence.adapter.serialization.bundleDigest
import org.ergon.controlplane.evidence.application.EvidenceBundleConflict
import org.ergon.controlplane.evidence.application.EvidenceBundleRegistration
import org.ergon.controlplane.evidence.application.EvidenceBundleRepository
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.evidence.application.InvalidEvidenceBundle
import org.ergon.controlplane.evidence.application.StoredEvidenceBundle
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceBundleManifest
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/** Fixed C-collation lock order is shared with SQL admission; exact retries recover before current-epoch validation. */
@Repository
class PostgresEvidenceBundleRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val codec: EvidenceBundleCodec,
) : EvidenceBundleRepository {
    override fun publish(
        admission: EvidenceReviewAdmission,
        command: EvidenceBundleCommand,
        content: EvidenceBundleContent,
    ): EvidenceBundleRegistration =
        try {
            checkNotNull(transactions.execute { publishLocked(admission, command, content) })
        } catch (error: DataAccessException) {
            val state =
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<SQLException>()
                    .map { it.sqlState }
                    .firstOrNull { it in setOf("P7701", "P7702", "P7703") }
            when (state) {
                "P7701" -> throw SourceUnavailable()
                "P7702" -> throw EvidenceBundleConflict()
                "P7703" -> throw InvalidEvidenceBundle()
                else -> throw error
            }
        }

    private fun publishLocked(
        admission: EvidenceReviewAdmission,
        command: EvidenceBundleCommand,
        content: EvidenceBundleContent,
    ): EvidenceBundleRegistration {
        lockIdentity(admission.tenant, command.id)
        lockClaimSets(admission.tenant, content)
        requireAuthority(admission)
        val previous = find(admission.tenant, command.id)
        if (previous != null) return recover(previous, admission, command, false)
        val cutoff =
            jdbc
                .sql("SELECT clock_timestamp()")
                .query(OffsetDateTime::class.java)
                .single()
                .toInstant()
        val manifest = EvidenceBundleManifest(cutoff, content)
        val encoded = codec.encode(admission.tenant, manifest)
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_bundles
                      (tenant_id, bundle_id, publication_command, canonical_manifest, manifest_sha256,
                       assessed_at, published_by, authority_evidence_id)
                    VALUES (:tenant, :id, :command, :manifest, :digest, :cutoff, :actor, :authority)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to admission.tenant.value,
                        "id" to command.id,
                        "command" to codec.encodeCommand(command),
                        "manifest" to encoded,
                        "digest" to bundleDigest(encoded),
                        "cutoff" to cutoff.atOffset(java.time.ZoneOffset.UTC),
                        "actor" to admission.reviewer.value,
                        "authority" to admission.authority.value,
                    ),
                ).update() == 1
        if (inserted) insertReferences(admission.tenant, command.id, content)
        return recover(checkNotNull(find(admission.tenant, command.id)), admission, command, inserted)
    }

    private fun lockIdentity(
        tenant: TenantId,
        id: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO evidence_bundle_publication_keys (tenant_id, bundle_id) VALUES (:tenant, :id)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", id)
            .update()
        jdbc
            .sql(
                """
                SELECT bundle_id FROM evidence_bundle_publication_keys
                WHERE tenant_id = :tenant AND bundle_id = :id FOR UPDATE
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("id", id)
            .query(UUID::class.java)
            .single()
    }

    private fun lockClaimSets(
        tenant: TenantId,
        content: EvidenceBundleContent,
    ) {
        jdbc
            .sql(
                """
                SELECT r.revision FROM evidence_claim_set_revisions r
                JOIN jsonb_to_recordset(CAST(:groups AS JSONB)) AS g(property TEXT, subject TEXT, scope TEXT)
                  ON r.property = g.property AND r.subject = g.subject AND r.scope = g.scope
                WHERE r.tenant_id = :tenant
                ORDER BY r.property COLLATE "C", r.subject COLLATE "C", r.scope COLLATE "C" FOR UPDATE OF r
                """.trimIndent(),
            ).param("groups", codec.groups(content))
            .param("tenant", tenant.value)
            .query(Long::class.java)
            .list()
    }

    override fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredEvidenceBundle? =
        jdbc
            .sql("SELECT * FROM evidence_bundles WHERE tenant_id = :tenant AND bundle_id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query { row, _ ->
                val text = row.getString("canonical_manifest")
                val manifest = codec.decode(tenant, text)
                check(bundleDigest(text) == row.getString("manifest_sha256")) { "stored manifest digest mismatch" }
                check(codec.encode(tenant, manifest) == text) { "stored manifest is not canonical v1" }
                check(manifest.assessedAt == row.getObject("assessed_at", OffsetDateTime::class.java).toInstant())
                StoredEvidenceBundle(
                    codec.decodeCommand(id, row.getString("publication_command")),
                    manifest,
                    row.getString("manifest_sha256"),
                    HumanActorId(row.getObject("published_by", UUID::class.java)),
                    ApprovalAuthorityEvidenceId(row.getObject("authority_evidence_id", UUID::class.java)),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    private fun insertReferences(
        tenant: TenantId,
        id: UUID,
        content: EvidenceBundleContent,
    ) {
        for (proposal in content.proposals) {
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_bundle_proposals
                      (tenant_id, bundle_id, kind, proposal_id, claim_id, procedure_id, review_command_id)
                    VALUES (:tenant, :bundle, :kind, :id, :claim, :procedure, :review)
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to tenant.value,
                        "bundle" to id,
                        "kind" to proposal.target.kind.name,
                        "id" to proposal.target.id,
                        "claim" to proposal.target.id.takeIf { proposal.target.kind == EvidenceProposalKind.CLAIM },
                        "procedure" to
                            proposal.target.id.takeIf {
                                proposal.target.kind == EvidenceProposalKind.PROCEDURE
                            },
                        "review" to proposal.reviewCommandId,
                    ),
                ).update()
        }
        for (decision in content.claimSets.mapNotNull { it.adjudicationId }) {
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_bundle_adjudications (tenant_id, bundle_id, adjudication_id)
                    VALUES (:tenant, :bundle, :decision)
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("bundle", id)
                .param("decision", decision)
                .update()
        }
        for (source in content.sources) {
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_bundle_sources (tenant_id, bundle_id, source_id, digest)
                    VALUES (:tenant, :bundle, :source, :digest)
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("bundle", id)
                .param("source", source.sourceId.value)
                .param("digest", source.digest.value)
                .update()
        }
    }

    private fun requireAuthority(admission: EvidenceReviewAdmission) {
        val current =
            jdbc
                .sql(
                    """
                    SELECT EXISTS (SELECT 1 FROM approval_authority_evidence
                    WHERE tenant_id = :tenant AND evidence_id = :authority AND actor_id = :actor
                      AND authority = 'RESOLVER' AND case_id IS NULL
                      AND attested_at <= clock_timestamp() AND expires_at > clock_timestamp())
                    """.trimIndent(),
                ).param("tenant", admission.tenant.value)
                .param("actor", admission.reviewer.value)
                .param("authority", admission.authority.value)
                .query(Boolean::class.java)
                .single()
        if (!current) throw SourceUnavailable()
    }

    private fun recover(
        stored: StoredEvidenceBundle,
        admission: EvidenceReviewAdmission,
        command: EvidenceBundleCommand,
        created: Boolean,
    ): EvidenceBundleRegistration {
        if (stored.command != command || stored.publisher != admission.reviewer) throw EvidenceBundleConflict()
        return EvidenceBundleRegistration(stored, created)
    }
}
