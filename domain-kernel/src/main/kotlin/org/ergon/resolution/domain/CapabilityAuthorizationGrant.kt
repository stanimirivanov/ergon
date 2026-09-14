package org.ergon.resolution.domain

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable, single-use capability authorization. */
@JvmInline
value class CapabilityAuthorizationGrantId(
    val value: UUID,
)

/** Run, request, and human decision whose exact relationship a grant must preserve. */
data class CapabilityAuthorizationGrantBasis(
    val run: ResolutionRunStart,
    val request: ApprovalRequest,
    val decision: ApprovalDecision,
)

/** Complete immutable values needed to rehydrate a previously validated grant. */
data class CapabilityAuthorizationGrantSnapshot(
    val id: CapabilityAuthorizationGrantId,
    val approvalDecisionId: ApprovalDecisionId,
    val approvalRequestId: ApprovalRequestId,
    val runId: ResolutionRunId,
    val caseId: CaseId,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val authorizedAt: Instant,
    val expiresAt: Instant,
)

/**
 * Narrow authorization to attempt one invocation of one pinned run step.
 *
 * [runId], [caseId], [policyRevision], [stepId], and [capability] preserve the
 * immutable scope authorized by [approvalDecisionId]. The half-open validity
 * interval begins at [authorizedAt] and ends at [expiresAt], which is inherited
 * from the approval request. A future consumer must atomically spend this grant
 * at most once; the grant itself performs no invocation and exposes no credential.
 *
 * @throws IllegalArgumentException when reconstructed with an empty validity
 *   interval. Use [derive] to validate all source relationships.
 */
@ConsistentCopyVisibility
data class CapabilityAuthorizationGrant private constructor(
    val id: CapabilityAuthorizationGrantId,
    val approvalDecisionId: ApprovalDecisionId,
    val approvalRequestId: ApprovalRequestId,
    val runId: ResolutionRunId,
    val caseId: CaseId,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val authorizedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(expiresAt.isAfter(authorizedAt)) { "authorization grant expiry must be after authorization time" }
    }

    companion object {
        /**
         * Derives an exact, bounded grant from a still-current approved decision.
         *
         * @throws IllegalArgumentException when the decision is not approved, the
         *   request has expired, authorization predates the decision, or any copied
         *   run, request, case, step, or authority relationship is inconsistent.
         */
        fun derive(
            id: CapabilityAuthorizationGrantId,
            basis: CapabilityAuthorizationGrantBasis,
            authorizedAt: Instant,
        ): CapabilityAuthorizationGrant {
            val (run, request, decision) = basis
            require(decision.outcome == ApprovalDecisionOutcome.APPROVED) {
                "authorization grant requires an approved decision"
            }
            require(!authorizedAt.isBefore(decision.decidedAt)) {
                "authorization grant predates its approval decision"
            }
            require(request.statusAt(authorizedAt) == ApprovalRequestStatus.PENDING) {
                "authorization grant approval request is expired"
            }
            require(request.runId == run.id && decision.runId == run.id) {
                "authorization grant sources do not identify the same run"
            }
            require(request.stepId == run.stepId) {
                "authorization grant request does not identify the run step"
            }
            require(decision.requestId == request.id) {
                "authorization grant decision does not answer the request"
            }
            require(decision.caseId == run.caseId) {
                "authorization grant decision does not identify the run case"
            }
            require(decision.authority.matches(run.requiredApproval)) {
                "authorization grant decision does not satisfy the run approval requirement"
            }
            return CapabilityAuthorizationGrant(
                id = id,
                approvalDecisionId = decision.id,
                approvalRequestId = request.id,
                runId = run.id,
                caseId = run.caseId,
                policyRevision = run.policyRevision,
                stepId = run.stepId,
                capability = run.capability,
                authorizedAt = authorizedAt,
                expiresAt = request.expiresAt,
            )
        }

        /**
         * Rehydrates a grant whose provenance was validated when recorded and
         * remains protected by durable constraints.
         *
         * @throws IllegalArgumentException when the stored validity interval is empty.
         */
        fun rehydrate(snapshot: CapabilityAuthorizationGrantSnapshot): CapabilityAuthorizationGrant =
            CapabilityAuthorizationGrant(
                id = snapshot.id,
                approvalDecisionId = snapshot.approvalDecisionId,
                approvalRequestId = snapshot.approvalRequestId,
                runId = snapshot.runId,
                caseId = snapshot.caseId,
                policyRevision = snapshot.policyRevision,
                stepId = snapshot.stepId,
                capability = snapshot.capability,
                authorizedAt = snapshot.authorizedAt,
                expiresAt = snapshot.expiresAt,
            )
    }
}

private fun ApprovalAuthority.matches(requirement: ApprovalRequirement): Boolean =
    when (this) {
        ApprovalAuthority.REQUESTER -> requirement == ApprovalRequirement.REQUESTER
        ApprovalAuthority.RESOLVER -> requirement == ApprovalRequirement.RESOLVER
    }
