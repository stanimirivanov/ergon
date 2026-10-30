package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.adapter.serialization.EvidenceBundleCodec
import org.ergon.controlplane.evidence.adapter.serialization.bundleDigest
import org.ergon.controlplane.evidence.application.EvidenceReviewReceipt
import org.ergon.controlplane.evidence.application.StoredEvidenceBundle
import org.ergon.controlplane.evidence.application.StoredProcedureProposal
import org.ergon.evidence.domain.BundleClaimSet
import org.ergon.evidence.domain.BundleClaimSetSelection
import org.ergon.evidence.domain.BundleProposalReference
import org.ergon.evidence.domain.EvidenceBundleCommand
import org.ergon.evidence.domain.EvidenceBundleContent
import org.ergon.evidence.domain.EvidenceBundleManifest
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewDecision
import org.ergon.evidence.domain.EvidenceReviewTarget
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

internal class EvidenceBundleFixtures {
    val f = ContradictionFixtures()
    val command =
        EvidenceBundleCommand(
            UUID(0, 50),
            listOf(f.command.anchorId),
            listOf(f.source.command.id),
            listOf(BundleClaimSetSelection(f.command.anchorId, 2, f.command.candidateIds, f.command.id)),
        )
    val procedure = StoredProcedureProposal(f.source.proposal(), f.source.actor, f.now)
    val procedureReview =
        EvidenceReviewReceipt(
            EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, f.source.command.id),
            EvidenceReviewCommand(
                UUID(0, 60),
                f.source.compiled.extraction.id,
                0,
                EvidenceReviewDecision.ACCEPTED,
                "Reviewed",
            ),
            f.source.actor,
            f.authority,
            f.now,
        )
    val content =
        EvidenceBundleContent(
            f.candidates.map {
                BundleProposalReference(
                    EvidenceReviewTarget(EvidenceProposalKind.CLAIM, it.stored.proposal.id),
                    it.stored.proposal.extraction,
                    it.reviewCommandId,
                    it.stored.proposal.id == f.command.anchorId,
                )
            } +
                BundleProposalReference(
                    procedureReview.target,
                    procedure.proposal.extraction,
                    procedureReview.command.id,
                    true,
                ),
            listOf(f.source.markdown.revision, f.source.api.revision),
            listOf(
                BundleClaimSet(
                    f.key,
                    2,
                    f.command.candidateIds,
                    f.stored().reviewCommands,
                    f.stored().findingCodes,
                    f.command.id,
                ),
            ),
        )
    val manifest = EvidenceBundleManifest(f.now, content)
    val codec = EvidenceBundleCodec()
    val stored =
        StoredEvidenceBundle(
            command,
            manifest,
            bundleDigest(codec.encode(f.source.tenant, manifest)),
            f.source.actor,
            f.authority,
            f.now,
        )
}
