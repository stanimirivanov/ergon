package org.ergon.controlplane.evidence.application

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.knowledge.application.SourceUnavailable
import org.ergon.evidence.domain.EvidenceExtraction
import org.ergon.evidence.domain.EvidenceProposalKind
import org.ergon.evidence.domain.EvidenceReviewCommand
import org.ergon.evidence.domain.EvidenceReviewTarget
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Current attestation selected by the application; persistence rechecks it after target-lock waits. */
data class EvidenceReviewAdmission(
    val tenant: TenantId,
    val reviewer: HumanActorId,
    val authority: ApprovalAuthorityEvidenceId,
)

data class EvidenceReviewReceipt(
    val target: EvidenceReviewTarget,
    val command: EvidenceReviewCommand,
    val reviewer: HumanActorId,
    val authority: ApprovalAuthorityEvidenceId,
    val recordedAt: Instant,
)

data class EvidenceReviewRegistration(
    val receipt: EvidenceReviewReceipt,
    val created: Boolean,
)

/** Keyset coordinates are exact database microseconds; kind ordering is explicit, not locale/enum ordinal dependent. */
data class EvidenceReviewCursor(
    val recordedAt: Instant,
    val kind: EvidenceProposalKind,
    val id: UUID,
) {
    init {
        require(recordedAt.nano % NANOS_PER_MICROSECOND == 0)
        require(recordedAt >= MIN_TIME && recordedAt <= MAX_TIME)
    }

    companion object {
        private const val NANOS_PER_MICROSECOND = 1000
        private val MIN_TIME = Instant.parse("0001-01-01T00:00:00Z")
        private val MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z")
    }
}

data class PendingEvidenceProposal(
    val target: EvidenceReviewTarget,
    val extraction: EvidenceExtraction,
    val proposedBy: HumanActorId,
    val recordedAt: Instant,
    val label: String,
)

data class EvidenceReviewPage(
    val entries: List<PendingEvidenceProposal>,
    val next: EvidenceReviewCursor?,
)

sealed interface ReviewableEvidenceProposal {
    val target: EvidenceReviewTarget
    val extraction: EvidenceExtraction

    data class Claim(
        val stored: StoredClaimProposal,
    ) : ReviewableEvidenceProposal {
        override val target get() = EvidenceReviewTarget(EvidenceProposalKind.CLAIM, stored.proposal.id)
        override val extraction get() = stored.proposal.extraction
    }

    data class Procedure(
        val stored: StoredProcedureProposal,
    ) : ReviewableEvidenceProposal {
        override val target get() = EvidenceReviewTarget(EvidenceProposalKind.PROCEDURE, stored.proposal.id)
        override val extraction get() = stored.proposal.extraction
    }
}

/** Requires an already authorized reviewer; implementations reverify private source citations. */
fun interface EvidenceReviewProposalReader {
    fun get(
        tenant: TenantId,
        actor: HumanActorId,
        target: EvidenceReviewTarget,
    ): ReviewableEvidenceProposal
}

/** Terminal decisions and idempotent receipts are immutable; absence is distinct from infrastructure failure. */
interface EvidenceReviewRepository {
    fun decide(
        admission: EvidenceReviewAdmission,
        target: EvidenceReviewTarget,
        command: EvidenceReviewCommand,
    ): EvidenceReviewRegistration

    fun find(
        tenant: TenantId,
        target: EvidenceReviewTarget,
    ): EvidenceReviewReceipt?

    fun pending(
        tenant: TenantId,
        before: EvidenceReviewCursor?,
        limit: Int,
    ): List<PendingEvidenceProposal>
}

data class EvidenceReviewInspection(
    val proposal: ReviewableEvidenceProposal,
    val receipt: EvidenceReviewReceipt?,
)

class InvalidEvidenceReview : RuntimeException()

class EvidenceReviewConflict : RuntimeException()

/** Snapshot authorization on every call, including exact receipt recovery; no continuous revocation promise. */
class EvidenceReviewService(
    private val authorities: HumanAuthorityRepository,
    private val proposals: EvidenceReviewProposalReader,
    private val repository: EvidenceReviewRepository,
    private val clock: Clock,
) {
    fun decide(
        tenant: TenantId,
        actor: HumanActorId,
        target: EvidenceReviewTarget,
        command: EvidenceReviewCommand,
    ): EvidenceReviewRegistration {
        val admission = authorize(tenant, actor)
        val proposal = proposals.get(tenant, actor, target)
        if (proposal.extraction.id != command.extractionId) throw EvidenceReviewConflict()
        return repository.decide(admission, target, command)
    }

    fun inspect(
        tenant: TenantId,
        actor: HumanActorId,
        target: EvidenceReviewTarget,
    ): EvidenceReviewInspection {
        authorize(tenant, actor)
        val proposal = proposals.get(tenant, actor, target)
        val receipt = repository.find(tenant, target)
        check(receipt == null || receipt.command.extractionId == proposal.extraction.id) {
            "review extraction is inconsistent"
        }
        return EvidenceReviewInspection(proposal, receipt)
    }

    fun pending(
        tenant: TenantId,
        actor: HumanActorId,
        before: EvidenceReviewCursor?,
        limit: Int,
    ): EvidenceReviewPage {
        authorize(tenant, actor)
        if (limit !in 1..MAX_PAGE_SIZE) throw InvalidEvidenceReview()
        val candidates = repository.pending(tenant, before, limit + 1)
        val entries = candidates.take(limit)
        val next =
            if (candidates.size > limit) {
                entries.last().let {
                    EvidenceReviewCursor(it.recordedAt, it.target.kind, it.target.id)
                }
            } else {
                null
            }
        return EvidenceReviewPage(entries, next)
    }

    private fun authorize(
        tenant: TenantId,
        actor: HumanActorId,
    ): EvidenceReviewAdmission {
        val proof =
            authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
                ?: throw SourceUnavailable()
        return EvidenceReviewAdmission(tenant, actor, proof.evidence.id)
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
    }
}
