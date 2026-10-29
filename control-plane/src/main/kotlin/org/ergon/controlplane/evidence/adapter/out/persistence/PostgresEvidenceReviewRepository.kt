package org.ergon.controlplane.evidence.adapter.out.persistence

import org.ergon.controlplane.evidence.application.EvidenceReviewAdmission
import org.ergon.controlplane.evidence.application.EvidenceReviewConflict
import org.ergon.controlplane.evidence.application.EvidenceReviewCursor
import org.ergon.controlplane.evidence.application.EvidenceReviewReceipt
import org.ergon.controlplane.evidence.application.EvidenceReviewRegistration
import org.ergon.controlplane.evidence.application.EvidenceReviewRepository
import org.ergon.controlplane.evidence.application.EvidenceReviewService
import org.ergon.controlplane.evidence.application.PendingEvidenceProposal
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.evidence.domain.SourceDigest
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceRevision
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
import java.time.ZoneOffset
import java.util.UUID

/** Proposal locks serialize decisions; database time rechecks authority after any lock wait, including retries. */
@Repository
class PostgresEvidenceReviewRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : EvidenceReviewRepository {
    override fun decide(
        admission: EvidenceReviewAdmission,
        target: EvidenceReviewTarget,
        command: EvidenceReviewCommand,
    ): EvidenceReviewRegistration =
        try {
            checkNotNull(transactions.execute { register(admission, target, command) })
        } catch (error: DataAccessException) {
            if (generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState == "P7501" }
            ) {
                throw SourceUnavailable()
            }
            throw error
        }

    private fun register(
        admission: EvidenceReviewAdmission,
        target: EvidenceReviewTarget,
        command: EvidenceReviewCommand,
    ): EvidenceReviewRegistration {
        val table =
            when (target.kind) {
                EvidenceProposalKind.CLAIM -> "evidence_claim_proposals"
                EvidenceProposalKind.PROCEDURE -> "evidence_procedure_proposals"
            }
        val extraction =
            jdbc
                .sql(
                    "SELECT extraction_id FROM $table WHERE tenant_id = :tenant AND proposal_id = :id FOR UPDATE",
                ).param("tenant", admission.tenant.value)
                .param("id", target.id)
                .query(UUID::class.java)
                .optional()
                .orElseThrow { SourceUnavailable() }
        requireCurrentAuthority(admission)
        val previous = byCommand(admission.tenant, command.id)
        if (previous != null) return recover(previous, admission, target, command)
        if (extraction != command.extractionId || command.expectedVersion != EvidenceReviewCommand.PENDING_VERSION) {
            throw EvidenceReviewConflict()
        }
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_proposal_reviews
                        (tenant_id, proposal_kind, proposal_id, claim_id, procedure_id, command_id, extraction_id,
                         decision, rationale, reviewer_id, authority_evidence_id)
                    VALUES (:tenant, :kind, :id, :claim, :procedure, :command, :extraction,
                            :decision, :rationale, :actor, :authority)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to admission.tenant.value,
                        "kind" to target.kind.name,
                        "id" to target.id,
                        "claim" to target.id.takeIf { target.kind == EvidenceProposalKind.CLAIM },
                        "procedure" to target.id.takeIf { target.kind == EvidenceProposalKind.PROCEDURE },
                        "command" to command.id,
                        "extraction" to command.extractionId,
                        "decision" to command.decision.name,
                        "rationale" to command.rationale,
                        "actor" to admission.reviewer.value,
                        "authority" to admission.authority.value,
                    ),
                ).update() == 1
        val receipt = byCommand(admission.tenant, command.id) ?: throw EvidenceReviewConflict()
        return recover(receipt, admission, target, command).copy(created = inserted)
    }

    private fun recover(
        receipt: EvidenceReviewReceipt,
        admission: EvidenceReviewAdmission,
        target: EvidenceReviewTarget,
        command: EvidenceReviewCommand,
    ): EvidenceReviewRegistration {
        if (receipt.target != target || receipt.command != command || receipt.reviewer != admission.reviewer) {
            throw EvidenceReviewConflict()
        }
        return EvidenceReviewRegistration(receipt, false)
    }

    private fun requireCurrentAuthority(admission: EvidenceReviewAdmission) {
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

    override fun find(
        tenant: TenantId,
        target: EvidenceReviewTarget,
    ): EvidenceReviewReceipt? =
        jdbc
            .sql(
                """
                SELECT * FROM evidence_proposal_reviews
                WHERE tenant_id = :tenant AND proposal_kind = :kind AND proposal_id = :id
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("kind", target.kind.name)
            .param("id", target.id)
            .query { row, _ -> receipt(row) }
            .optional()
            .orElse(null)

    private fun byCommand(
        tenant: TenantId,
        command: UUID,
    ): EvidenceReviewReceipt? =
        jdbc
            .sql("SELECT * FROM evidence_proposal_reviews WHERE tenant_id = :tenant AND command_id = :command")
            .param("tenant", tenant.value)
            .param("command", command)
            .query { row, _ -> receipt(row) }
            .optional()
            .orElse(null)

    override fun pending(
        tenant: TenantId,
        before: EvidenceReviewCursor?,
        limit: Int,
    ): List<PendingEvidenceProposal> {
        require(limit in 1..EvidenceReviewService.MAX_PAGE_SIZE + 1)
        val cursor =
            if (before == null) {
                ""
            } else {
                "WHERE (recorded_at, kind_sort, proposal_id) < (:before_time, :before_kind, :before_id)"
            }
        // Select only shared metadata; documentary contents are reverified by the inspection endpoint.
        val statement =
            jdbc
                .sql(
                    """
                    SELECT * FROM (
                      SELECT proposal_id, source_id, digest, extraction_id, compiler_revision, proposed_by, recorded_at,
                             'CLAIM' AS kind, 0 AS kind_sort, subject AS label
                      FROM evidence_claim_proposals p WHERE tenant_id = :tenant AND NOT EXISTS (
                        SELECT 1 FROM evidence_proposal_reviews r WHERE r.tenant_id = p.tenant_id
                        AND r.proposal_kind = 'CLAIM' AND r.proposal_id = p.proposal_id)
                      UNION ALL
                      SELECT proposal_id, source_id, digest, extraction_id, compiler_revision, proposed_by, recorded_at,
                             'PROCEDURE' AS kind, 1 AS kind_sort, title AS label
                      FROM evidence_procedure_proposals p WHERE tenant_id = :tenant AND NOT EXISTS (
                        SELECT 1 FROM evidence_proposal_reviews r WHERE r.tenant_id = p.tenant_id
                        AND r.proposal_kind = 'PROCEDURE' AND r.proposal_id = p.proposal_id)
                    ) pending
                    $cursor
                    ORDER BY recorded_at DESC, kind_sort DESC, proposal_id DESC LIMIT :limit
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("limit", limit)
        if (before != null) {
            statement
                .param("before_time", before.recordedAt.atOffset(ZoneOffset.UTC))
                .param("before_kind", before.kind.queueOrder)
                .param("before_id", before.id)
        }
        return statement
            .query { row, _ ->
                PendingEvidenceProposal(
                    EvidenceReviewTarget(EvidenceProposalKind.valueOf(row.getString("kind")), row.uuid("proposal_id")),
                    EvidenceExtraction(
                        row.uuid("extraction_id"),
                        SourceRevision(tenant, SourceId(row.uuid("source_id")), SourceDigest(row.getString("digest"))),
                        row.getString("compiler_revision"),
                    ),
                    HumanActorId(row.uuid("proposed_by")),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                    row.getString("label"),
                )
            }.list()
    }

    private fun receipt(row: ResultSet): EvidenceReviewReceipt {
        check(row.getLong("review_version") == EvidenceReviewCommand.DECIDED_VERSION)
        return EvidenceReviewReceipt(
            EvidenceReviewTarget(EvidenceProposalKind.valueOf(row.getString("proposal_kind")), row.uuid("proposal_id")),
            EvidenceReviewCommand(
                row.uuid("command_id"),
                row.uuid("extraction_id"),
                EvidenceReviewCommand.PENDING_VERSION,
                EvidenceReviewDecision.valueOf(row.getString("decision")),
                row.getString("rationale"),
            ),
            HumanActorId(row.uuid("reviewer_id")),
            ApprovalAuthorityEvidenceId(row.uuid("authority_evidence_id")),
            row.getObject("reviewed_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
}
