package org.ergon.resolution.domain

import org.ergon.cases.domain.CaseId
import org.ergon.identity.domain.HumanActorId
import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable response to an approval request. */
@JvmInline
value class ApprovalDecisionId(
    val value: UUID,
)

/** Human response recorded for an approval request; neither outcome authorizes execution. */
enum class ApprovalDecisionOutcome {
    APPROVED,
    REJECTED,
}

/** Request, case, and authority evidence whose relationship a decision must validate atomically. */
data class ApprovalDecisionBasis(
    val request: ApprovalRequest,
    val caseId: CaseId,
    val evidence: ApprovalAuthorityEvidence,
)

/**
 * Immutable, attributable response to one current approval request.
 *
 * [authorityEvidenceId] identifies the current attestation used to establish
 * [actorId]'s required authority at [decidedAt]. Requester evidence must name
 * [caseId], while resolver evidence must be tenant-wide. Recording an approved
 * outcome satisfies a human prerequisite only; it does not grant a capability
 * or advance the associated resolution run.
 */
@ConsistentCopyVisibility
data class ApprovalDecision private constructor(
    val id: ApprovalDecisionId,
    val requestId: ApprovalRequestId,
    val runId: ResolutionRunId,
    val actorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
    val authority: ApprovalAuthority,
    val caseId: CaseId,
    val outcome: ApprovalDecisionOutcome,
    val decidedAt: Instant,
) {
    companion object {
        /**
         * Records [outcome] against [basis] as its request and evidence exist at [decidedAt].
         *
         * @throws IllegalArgumentException when the decision predates the request or
         *   evidence, the request or evidence is expired, or the evidence does not
         *   establish the request's authority for its case.
         */
        fun record(
            id: ApprovalDecisionId,
            basis: ApprovalDecisionBasis,
            outcome: ApprovalDecisionOutcome,
            decidedAt: Instant,
        ): ApprovalDecision {
            val (request, caseId, evidence) = basis
            require(!decidedAt.isBefore(request.requestedAt)) { "approval decision predates its request" }
            require(request.statusAt(decidedAt) == ApprovalRequestStatus.PENDING) { "approval request is expired" }
            require(!decidedAt.isBefore(evidence.attestedAt)) { "approval decision predates its authority evidence" }
            require(evidence.statusAt(decidedAt) == ApprovalAuthorityEvidenceStatus.CURRENT) {
                "approval authority evidence is expired"
            }
            require(evidence.authority == request.authority) {
                "approval authority evidence does not satisfy the request"
            }
            val requiredCaseId = caseId.takeIf { request.authority == ApprovalAuthority.REQUESTER }
            require(evidence.caseId == requiredCaseId) {
                "approval authority evidence does not match the required scope"
            }
            return ApprovalDecision(
                id = id,
                requestId = request.id,
                runId = request.runId,
                actorId = evidence.actorId,
                authorityEvidenceId = evidence.id,
                authority = request.authority,
                caseId = caseId,
                outcome = outcome,
                decidedAt = decidedAt,
            )
        }
    }
}
