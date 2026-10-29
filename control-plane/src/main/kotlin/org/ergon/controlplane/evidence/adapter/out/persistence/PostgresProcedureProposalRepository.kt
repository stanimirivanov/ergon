package org.ergon.controlplane.evidence.adapter.out.persistence

import org.ergon.controlplane.evidence.application.ProcedureConflict
import org.ergon.controlplane.evidence.application.ProcedureProposalRepository
import org.ergon.controlplane.evidence.application.ProcedureRegistration
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.ProcedureProposal
import org.ergon.evidence.domain.SourceDigest
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceRevision
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

/** Definition and operation-source foreign keys commit together; exact retry recovery never appends new links. */
@Repository
class PostgresProcedureProposalRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val codec: ProcedureDefinitionCodec,
) : ProcedureProposalRepository {
    override fun register(
        proposal: ProcedureProposal,
        actor: HumanActorId,
    ): ProcedureRegistration =
        checkNotNull(
            transactions.execute {
                val source = proposal.extraction.source
                val inserted =
                    jdbc
                        .sql(
                            """
                            INSERT INTO evidence_procedure_proposals
                                (tenant_id, proposal_id, source_id, digest, extraction_id, compiler_revision,
                                 observation_index, title, step_count, definition, proposed_by)
                            VALUES (:tenant, :id, :source, :digest, :extraction, :compiler,
                                    :index, :title, :count, CAST(:definition AS JSONB), :actor)
                            ON CONFLICT (tenant_id, proposal_id) DO NOTHING
                            """.trimIndent(),
                        ).params(
                            mapOf(
                                "tenant" to source.tenantId.value,
                                "id" to proposal.id,
                                "source" to source.sourceId.value,
                                "digest" to source.digest.value,
                                "extraction" to proposal.extraction.id,
                                "compiler" to proposal.extraction.extractorRevision,
                                "index" to proposal.observationIndex,
                                "title" to proposal.content.title,
                                "count" to proposal.content.steps.size,
                                "definition" to codec.encode(proposal.content),
                                "actor" to actor.value,
                            ),
                        ).update() == 1
                if (inserted) insertReferences(proposal)
                val stored = checkNotNull(find(source.tenantId, proposal.id)) { "inserted procedure missing" }
                if (stored.proposal != proposal || stored.proposedBy != actor) throw ProcedureConflict()
                ProcedureRegistration(stored, inserted)
            },
        )

    override fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredProcedureProposal? =
        jdbc
            .sql(
                "SELECT * FROM evidence_procedure_proposals WHERE tenant_id = :tenant AND proposal_id = :id",
            ).param("tenant", tenant.value)
            .param("id", id)
            .query { row, _ ->
                val supportedSchema = row.getString("definition_schema") == SCHEMA
                check(supportedSchema && row.getString("binding_rule") == ProcedureProposal.BINDING_RULE) {
                    "unsupported procedure definition schema or binding rule"
                }
                val source =
                    SourceRevision(
                        tenant,
                        SourceId(row.getObject("source_id", UUID::class.java)),
                        SourceDigest(row.getString("digest")),
                    )
                val content = codec.decode(source, row.getString("title"), row.getString("definition"))
                check(content.steps.size == row.getInt("step_count")) { "stored procedure step count is inconsistent" }
                StoredProcedureProposal(
                    ProcedureProposal(
                        id,
                        EvidenceExtraction(
                            row.getObject("extraction_id", UUID::class.java),
                            source,
                            row.getString("compiler_revision"),
                        ),
                        row.getInt("observation_index"),
                        content,
                    ),
                    HumanActorId(row.getObject("proposed_by", UUID::class.java)),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    private fun insertReferences(proposal: ProcedureProposal) {
        for (step in proposal.content.steps) {
            val operation = step.operation ?: continue
            jdbc
                .sql(
                    """
                    INSERT INTO evidence_procedure_operation_sources
                        (tenant_id, proposal_id, step_position, source_id, digest)
                    VALUES (:tenant, :id, :position, :source, :digest)
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to proposal.extraction.source.tenantId.value,
                        "id" to proposal.id,
                        "position" to step.position,
                        "source" to operation.citation.revision.sourceId.value,
                        "digest" to operation.citation.revision.digest.value,
                    ),
                ).update()
        }
    }

    companion object {
        const val SCHEMA = "ergon.dev/procedure-definition/v1"
    }
}
