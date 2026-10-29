package org.ergon.controlplane.evidence.adapter.out.persistence

import org.ergon.controlplane.evidence.application.AcceptedClaimCandidate
import org.ergon.controlplane.evidence.application.AcceptedClaimSet
import org.ergon.controlplane.evidence.application.AssessedClaimSet
import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.ContradictionConflict
import org.ergon.controlplane.evidence.application.ContradictionRepository
import org.ergon.controlplane.evidence.application.ContradictionSetTooLarge
import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.evidence.application.StoredContradictionAdjudication
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.ClaimComparison
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.DocumentaryClaimKey
import org.ergon.evidence.domain.DocumentaryContradictions
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/** One short claim-set lock serializes assessment admission with every acceptance in that exact group. */
@Repository
class PostgresContradictionRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val claims: ClaimProposalRepository,
) : ContradictionRepository {
    override fun capture(
        tenant: TenantId,
        anchor: UUID,
    ): AcceptedClaimSet =
        checkNotNull(
            transactions.execute {
                val candidate = candidate(tenant, anchor) ?: throw SourceUnavailable()
                val key = DocumentaryClaimKey.of(candidate.stored.proposal.claim)
                val revision = lock(tenant, key)
                val candidates =
                    jdbc
                        .sql(
                            """
                            SELECT p.proposal_id FROM evidence_claim_proposals p
                            JOIN evidence_proposal_reviews r
                              ON r.tenant_id = p.tenant_id AND r.proposal_id = p.proposal_id
                              AND r.proposal_kind = 'CLAIM' AND r.decision = 'ACCEPTED'
                            WHERE p.tenant_id = :tenant AND p.property = :property
                              AND p.subject = :subject AND p.scope = :scope
                            ORDER BY p.proposal_id LIMIT :limit
                            """.trimIndent(),
                        ).params(keyParams(tenant, key))
                        .param("limit", DocumentaryContradictions.MAX_CANDIDATES + 1)
                        .query(UUID::class.java)
                        .list()
                        .map { checkNotNull(candidate(tenant, checkNotNull(it))) }
                if (candidates.size > DocumentaryContradictions.MAX_CANDIDATES) throw ContradictionSetTooLarge()
                AcceptedClaimSet(key, revision, candidates)
            },
        )

    override fun candidate(
        tenant: TenantId,
        id: UUID,
    ): AcceptedClaimCandidate? {
        val review =
            jdbc
                .sql(
                    """
                    SELECT command_id FROM evidence_proposal_reviews
                    WHERE tenant_id = :tenant AND proposal_kind = 'CLAIM'
                      AND proposal_id = :id AND decision = 'ACCEPTED'
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("id", id)
                .query(UUID::class.java)
                .optional()
                .orElse(null) ?: return null
        return AcceptedClaimCandidate(checkNotNull(claims.find(tenant, id)), review)
    }

    override fun currentRevision(
        tenant: TenantId,
        key: DocumentaryClaimKey,
    ): Long =
        jdbc
            .sql(
                """
                SELECT revision FROM evidence_claim_set_revisions
                WHERE tenant_id = :tenant AND property = :property AND subject = :subject AND scope = :scope
                """.trimIndent(),
            ).params(keyParams(tenant, key))
            .query(Long::class.java)
            .optional()
            .orElse(0)

    override fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredContradictionAdjudication? =
        jdbc
            .sql("SELECT * FROM evidence_contradiction_adjudications WHERE tenant_id = :tenant AND command_id = :id")
            .param("tenant", tenant.value)
            .param("id", id)
            .query { row, _ -> stored(row) }
            .optional()
            .orElse(null)

    override fun register(
        admission: EvidenceReviewAdmission,
        assessment: AssessedClaimSet,
        command: ContradictionCommand,
    ): Pair<StoredContradictionAdjudication, Boolean> =
        try {
            checkNotNull(transactions.execute { insert(admission, assessment, command) })
        } catch (error: DataAccessException) {
            val state =
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<SQLException>()
                    .map { it.sqlState }
                    .firstOrNull { it in setOf("P7601", "P7602") }
            when (state) {
                "P7601" -> throw SourceUnavailable()
                "P7602" -> throw ContradictionConflict()
                else -> throw error
            }
        }

    private fun insert(
        admission: EvidenceReviewAdmission,
        assessment: AssessedClaimSet,
        command: ContradictionCommand,
    ): Pair<StoredContradictionAdjudication, Boolean> {
        val current = lock(admission.tenant, assessment.set.key)
        requireAuthority(admission)
        val previous = find(admission.tenant, command.id)
        if (previous != null) return recover(previous, admission, command) to false
        if (current != command.expectedRevision || current != assessment.set.revision) throw ContradictionConflict()
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_contradiction_adjudications
                      (tenant_id, command_id, anchor_id, property, subject, scope, set_revision, candidate_ids,
                       review_command_ids, finding_codes, choice, selected_claim_id, rationale,
                        reviewer_id, authority_evidence_id)
                    VALUES (:tenant, :id, :anchor, :property, :subject, :scope, :revision, :candidates,
                            :reviews, :findings, :choice, :selected, :rationale, :actor, :authority)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).params(keyParams(admission.tenant, assessment.set.key))
                .params(
                    mapOf(
                        "id" to command.id,
                        "anchor" to command.anchorId,
                        "revision" to command.expectedRevision,
                        "candidates" to command.candidateIds.toTypedArray(),
                        "reviews" to
                            assessment.set.candidates
                                .map { it.reviewCommandId }
                                .toTypedArray(),
                        "findings" to assessment.findings.map { it.comparison.name }.toTypedArray(),
                        "choice" to command.choice.name,
                        "selected" to command.selectedClaimId,
                        "rationale" to command.rationale,
                        "actor" to admission.reviewer.value,
                        "authority" to admission.authority.value,
                    ),
                ).update() == 1
        val result = find(admission.tenant, command.id) ?: throw ContradictionConflict()
        return recover(result, admission, command) to inserted
    }

    private fun recover(
        stored: StoredContradictionAdjudication,
        admission: EvidenceReviewAdmission,
        command: ContradictionCommand,
    ): StoredContradictionAdjudication {
        if (stored.command != command || stored.reviewer != admission.reviewer) throw ContradictionConflict()
        return stored
    }

    private fun lock(
        tenant: TenantId,
        key: DocumentaryClaimKey,
    ): Long =
        jdbc
            .sql(
                """
                SELECT revision FROM evidence_claim_set_revisions
                WHERE tenant_id = :tenant AND property = :property AND subject = :subject AND scope = :scope FOR UPDATE
                """.trimIndent(),
            ).params(keyParams(tenant, key))
            .query(Long::class.java)
            .optional()
            .orElseThrow { SourceUnavailable() }

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

    private fun stored(row: ResultSet): StoredContradictionAdjudication {
        check(row.getString("algorithm") == DocumentaryContradictions.ALGORITHM) {
            "unsupported contradiction algorithm"
        }
        val ids = row.values("candidate_ids").map(UUID::fromString)
        return StoredContradictionAdjudication(
            ContradictionCommand(
                row.getObject("command_id", UUID::class.java),
                row.getObject("anchor_id", UUID::class.java),
                row.getLong("set_revision"),
                ids,
                ContradictionChoice.valueOf(row.getString("choice")),
                row.getObject("selected_claim_id", UUID::class.java),
                row.getString("rationale"),
            ),
            DocumentaryClaimKey(
                DocumentaryProperty.valueOf(row.getString("property")),
                row.getString("subject"),
                row.getString("scope"),
            ),
            row.values("review_command_ids").map(UUID::fromString),
            row.values("finding_codes").map(ClaimComparison::valueOf),
            HumanActorId(row.getObject("reviewer_id", UUID::class.java)),
            ApprovalAuthorityEvidenceId(row.getObject("authority_evidence_id", UUID::class.java)),
            row.getObject("adjudicated_at", OffsetDateTime::class.java).toInstant(),
        )
    }
}

private fun ResultSet.values(column: String): List<String> {
    val sql = getArray(column)
    return try {
        (sql.array as Array<*>).map { checkNotNull(it).toString() }
    } finally {
        sql.free()
    }
}

private fun keyParams(
    tenant: TenantId,
    key: DocumentaryClaimKey,
): Map<String, Any> =
    mapOf(
        "tenant" to tenant.value,
        "property" to key.property.name,
        "subject" to key.subject,
        "scope" to key.scope,
    )
