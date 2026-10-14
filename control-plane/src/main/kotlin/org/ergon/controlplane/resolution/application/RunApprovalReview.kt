package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequestStatus
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Instant
import java.util.UUID

/** Current request lifecycle, evaluated at the server observation instant; never an execution grant. */
enum class RunApprovalReviewStatus { NOT_REQUIRED, NOT_REQUESTED, PENDING, EXPIRED, DECIDED }

/** Sanitized immutable decision; attribution and authority evidence remain server-side. */
data class RunApprovalDecisionSummary(
    val decisionId: UUID,
    val outcome: ApprovalDecisionOutcome,
    val decidedAt: Instant,
    val recordedAt: Instant,
)

/** Latest request by durable recording order, optionally answered through either supported decision boundary. */
data class LatestRunApproval(
    val request: StoredApprovalRequest,
    val decision: RunApprovalDecisionSummary?,
)

/** Read port for the exact run's latest immutable request and its decision in one database observation. */
fun interface RunApprovalReviewReader {
    fun findLatest(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): LatestRunApproval?
}

/**
 * Review facts disclosed only through the current-supervisor Console gate.
 * [canDecide] is an observation, not lasting authority: the command rechecks all gates.
 * A decision outranks subsequent request expiry because its historical meaning is immutable.
 */
data class RunApprovalReview(
    val status: RunApprovalReviewStatus,
    val observedAt: Instant,
    val canDecide: Boolean,
    val latest: LatestRunApproval?,
)

/** Projects approval lifecycle and exact authority eligibility without copying private attestation data. */
class RunApprovalReviewService(
    private val reader: RunApprovalReviewReader,
    private val authorities: HumanAuthorityRepository,
    private val enabled: Boolean,
) {
    /** Caller must establish current supervisor access before loading these private request facts. */
    fun review(
        tenant: TenantId,
        run: ResolutionRunStart,
        state: ResolutionRunState,
        actor: HumanActorId,
        at: Instant,
    ): RunApprovalReview {
        if (run.requiredApproval == ApprovalRequirement.NONE) {
            return RunApprovalReview(RunApprovalReviewStatus.NOT_REQUIRED, at, false, null)
        }
        val latest =
            reader.findLatest(tenant, run.id)
        return latest?.let {
            val request = it.request.request
            check(
                request.runId == run.id && request.stepId == run.stepId &&
                    request.authority.name == run.requiredApproval.name,
            ) {
                "approval request does not match the pinned run"
            }
            val status =
                when {
                    it.decision != null -> RunApprovalReviewStatus.DECIDED
                    request.statusAt(at) == ApprovalRequestStatus.EXPIRED -> RunApprovalReviewStatus.EXPIRED
                    else -> RunApprovalReviewStatus.PENDING
                }
            val scope = run.caseId.takeIf { request.authority == ApprovalAuthority.REQUESTER }
            val eligible =
                enabled && status == RunApprovalReviewStatus.PENDING &&
                    state == ResolutionRunState.WAITING_FOR_APPROVAL &&
                    !at.isBefore(request.requestedAt) &&
                    authorities.findCurrent(tenant, actor, request.authority, scope, at) != null
            RunApprovalReview(status, at, eligible, it)
        } ?: RunApprovalReview(RunApprovalReviewStatus.NOT_REQUESTED, at, false, null)
    }
}
