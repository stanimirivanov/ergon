package org.ergon.controlplane.evidence.adapter.out.persistence

import org.ergon.controlplane.evidence.application.ClaimConflict
import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.ClaimRegistration
import org.ergon.controlplane.evidence.application.StoredClaimProposal
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.SourceCitation
import org.ergon.evidence.domain.SourceDigest
import org.ergon.evidence.domain.SourceId
import org.ergon.evidence.domain.SourceRevision
import org.ergon.evidence.domain.SourceSpan
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Append-only tenant-scoped proposal storage; conflict recovery uses a fresh READ COMMITTED statement. */
@Repository
class PostgresClaimProposalRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) : ClaimProposalRepository {
    override fun register(
        proposal: ClaimProposal,
        actor: HumanActorId,
    ): ClaimRegistration =
        checkNotNull(
            transactions.execute {
                val claim = proposal.claim
                val citation = proposal.citation
                val inserted =
                    jdbc
                        .sql(
                            """
                            INSERT INTO evidence_claim_proposals
                                (tenant_id, proposal_id, source_id, digest, extraction_id,
                                 compiler_revision, observation_index,
                                 span_start, span_end, quote, property, boolean_value, text_value, subject, scope,
                                 valid_from, valid_until, proposed_by)
                            VALUES (:tenant, :id, :source, :digest, :extraction, :compiler,
                                    :index, :start, :end, :quote,
                                    :property, :boolean, :text, :subject, :scope, :from, :until, :actor)
                            ON CONFLICT (tenant_id, proposal_id) DO NOTHING
                            """.trimIndent(),
                        ).params(
                            mapOf(
                                "tenant" to citation.revision.tenantId.value,
                                "id" to proposal.id,
                                "source" to citation.revision.sourceId.value,
                                "digest" to citation.revision.digest.value,
                                "extraction" to proposal.extraction.id,
                                "compiler" to proposal.extraction.extractorRevision,
                                "index" to proposal.observationIndex,
                                "start" to citation.span.start,
                                "end" to citation.span.endExclusive,
                                "quote" to citation.quote.toByteArray(Charsets.UTF_8),
                                "property" to claim.property.name,
                                "boolean" to (claim.value as? DocumentaryValue.Support)?.value,
                                "text" to (claim.value as? DocumentaryValue.Requirement)?.value,
                                "subject" to claim.subject,
                                "scope" to claim.scope,
                                "from" to claim.validFrom?.atOffset(ZoneOffset.UTC),
                                "until" to claim.validUntil?.atOffset(ZoneOffset.UTC),
                                "actor" to actor.value,
                            ),
                        ).update() == 1
                val stored = checkNotNull(find(citation.revision.tenantId, proposal.id)) { "inserted claim missing" }
                if (stored.proposal != proposal || stored.proposedBy != actor) throw ClaimConflict()
                ClaimRegistration(stored, inserted)
            },
        )

    override fun find(
        tenant: TenantId,
        id: UUID,
    ): StoredClaimProposal? =
        jdbc
            .sql(
                "SELECT * FROM evidence_claim_proposals WHERE tenant_id = :tenant AND proposal_id = :id",
            ).param("tenant", tenant.value)
            .param("id", id)
            .query { row, _ ->
                check(row.getString("binding_rule") == ClaimProposal.BINDING_RULE) { "unsupported claim binding rule" }
                val source =
                    SourceRevision(
                        tenant,
                        SourceId(row.getObject("source_id", UUID::class.java)),
                        SourceDigest(row.getString("digest")),
                    )
                val property = DocumentaryProperty.valueOf(row.getString("property"))
                val value =
                    if (property == DocumentaryProperty.SUPPORT) {
                        DocumentaryValue.Support(row.getBoolean("boolean_value"))
                    } else {
                        DocumentaryValue.Requirement(row.getString("text_value"))
                    }
                val claim =
                    DocumentaryClaim(
                        property,
                        value,
                        row.getString("subject"),
                        row.getString("scope"),
                        row.getObject("valid_from", OffsetDateTime::class.java)?.toInstant(),
                        row.getObject("valid_until", OffsetDateTime::class.java)?.toInstant(),
                    )
                StoredClaimProposal(
                    ClaimProposal(
                        id,
                        EvidenceExtraction(
                            row.getObject("extraction_id", UUID::class.java),
                            source,
                            row.getString("compiler_revision"),
                        ),
                        row.getInt("observation_index"),
                        SourceCitation(
                            source,
                            SourceSpan(row.getInt("span_start"), row.getInt("span_end")),
                            Charsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(row.getBytes("quote")))
                                .toString(),
                        ),
                        claim,
                    ),
                    HumanActorId(row.getObject("proposed_by", UUID::class.java)),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)
}
