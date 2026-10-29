package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.AcceptedClaimCandidate
import org.ergon.controlplane.evidence.application.AcceptedClaimSet
import org.ergon.controlplane.evidence.application.AssessedClaimSet
import org.ergon.controlplane.evidence.application.StoredClaimProposal
import org.ergon.controlplane.evidence.application.StoredContradictionAdjudication
import org.ergon.evidence.domain.ClaimProposal
import org.ergon.evidence.domain.ContradictionChoice
import org.ergon.evidence.domain.ContradictionCommand
import org.ergon.evidence.domain.DocumentaryClaim
import org.ergon.evidence.domain.DocumentaryClaimKey
import org.ergon.evidence.domain.DocumentaryContradictions
import org.ergon.evidence.domain.DocumentaryProperty
import org.ergon.evidence.domain.DocumentaryValue
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Instant
import java.util.UUID

internal class ContradictionFixtures {
    val source = ProcedureFixtures()
    val now = Instant.parse("2026-10-29T12:00:00Z")
    val key = DocumentaryClaimKey(DocumentaryProperty.SUPPORT, "recovery", "staff")
    val candidates = listOf(candidate(10, true), candidate(11, false))
    val set = AcceptedClaimSet(key, 2, candidates)
    val assessed = AssessedClaimSet(set, DocumentaryContradictions.assess(candidates.map { it.stored.proposal }))
    val command =
        ContradictionCommand(
            UUID(0, 20),
            candidates
                .first()
                .stored.proposal.id,
            2,
            candidates.map { it.stored.proposal.id },
            ContradictionChoice.PREFER_CLAIM,
            candidates
                .first()
                .stored.proposal.id,
            "Checked original sources",
        )
    val authority = ApprovalAuthorityEvidenceId(UUID(0, 30))

    fun candidate(
        id: Long,
        supported: Boolean,
    ): AcceptedClaimCandidate {
        val proposal =
            ClaimProposal(
                UUID(0, id),
                source.compiled.extraction,
                0,
                source.compiled.observations[0].citation,
                DocumentaryClaim(
                    key.property,
                    DocumentaryValue.Support(supported),
                    key.subject,
                    key.scope,
                    now.minusSeconds(60),
                    now.plusSeconds(60),
                ),
            )
        return AcceptedClaimCandidate(StoredClaimProposal(proposal, source.actor, now), UUID(0, id + 100))
    }

    fun stored() =
        StoredContradictionAdjudication(
            command,
            key,
            candidates.map { it.reviewCommandId },
            assessed.findings.map { it.comparison },
            source.actor,
            authority,
            now,
        )
}
