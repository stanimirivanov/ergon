package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresEvidenceBundleRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.PostgresProcedureProposalRepository
import org.ergon.controlplane.evidence.adapter.out.persistence.ProcedureDefinitionCodec
import org.ergon.controlplane.evidence.adapter.serialization.EvidenceBundleCodec
import org.ergon.controlplane.evidence.application.AssessedClaimSet
import org.ergon.controlplane.evidence.application.StoredContradictionAdjudication
import org.ergon.controlplane.knowledge.adapter.out.persistence.PostgresSourceRevisionRepository
import org.ergon.evidence.domain.BundleClaimSet
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.BundleProposalReference
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

internal class EvidenceBundleDatabase(
    postgres: PostgreSQLContainer,
    acceptProcedure: Boolean = true,
    migrationTarget: String = "latest",
) {
    val db = ContradictionDatabase(postgres, migrationTarget)
    val f = db.f
    val transactions = TransactionTemplate(DataSourceTransactionManager(db.data))
    val codec = EvidenceBundleCodec()
    val repository = PostgresEvidenceBundleRepository(db.jdbc, transactions, codec)
    val procedure = f.source.proposal()
    val procedureReviewId = UUID.randomUUID()
    val assessed = db.assess()
    val decision = db.repository.register(db.admission, assessed, db.command(assessed)).first
    val content: EvidenceBundleContent
    val command: EvidenceBundleCommand

    init {
        PostgresSourceRevisionRepository(db.jdbc, transactions).register(f.source.api, f.source.actor)
        PostgresProcedureProposalRepository(
            db.jdbc,
            transactions,
            ProcedureDefinitionCodec(JsonMapper.builder().build()),
        ).register(procedure, f.source.actor)
        if (acceptProcedure) {
            db.reviews.decide(
                db.admission,
                EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, procedure.id),
                EvidenceReviewCommand(
                    procedureReviewId,
                    procedure.extraction.id,
                    0,
                    EvidenceReviewDecision.ACCEPTED,
                    "Reviewed",
                ),
            )
        }
        content = prepare(assessed, decision)
        command =
            EvidenceBundleCommand(
                UUID.randomUUID(),
                listOf(f.command.anchorId),
                listOf(procedure.id),
                listOf(
                    BundleClaimSetSelection(
                        f.command.anchorId,
                        assessed.set.revision,
                        f.command.candidateIds,
                        decision.command.id,
                    ),
                ),
            )
    }

    fun prepare(
        assessment: AssessedClaimSet,
        adjudication: StoredContradictionAdjudication,
    ): EvidenceBundleContent =
        EvidenceBundleContent(
            assessment.set.candidates.map {
                BundleProposalReference(
                    EvidenceReviewTarget(EvidenceProposalKind.CLAIM, it.stored.proposal.id),
                    it.stored.proposal.extraction,
                    it.reviewCommandId,
                    it.stored.proposal.id == f.command.anchorId,
                )
            } +
                BundleProposalReference(
                    EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, procedure.id),
                    procedure.extraction,
                    procedureReviewId,
                    true,
                ),
            listOf(f.source.markdown.revision, f.source.api.revision),
            listOf(
                BundleClaimSet(
                    assessment.set.key,
                    assessment.set.revision,
                    assessment.set.candidates.map { it.stored.proposal.id },
                    assessment.set.candidates.map { it.reviewCommandId },
                    assessment.findings.map { it.comparison },
                    adjudication.command.id,
                ),
            ),
        )
}
