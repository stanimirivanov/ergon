package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecision
import org.ergon.resolution.domain.ApprovalDecisionBasis
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ApprovalRequestStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Immutable approval decision paired with the database instant at which it became durable. */
data class StoredApprovalDecision(
    val decision: ApprovalDecision,
    val recordedAt: Instant,
)

/** Durable tenant-scoped history of immutable human approval decisions. */
interface ApprovalDecisionRepository {
    /**
     * Finds the decision identity already recorded for [requestId] in [tenantId].
     *
     * @return the existing identity, or `null` when the request remains unanswered.
     */
    fun findIdByRequest(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): ApprovalDecisionId?

    /**
     * Persists [decision] once for its approval request.
     *
     * The caller must hold the request lock through this operation.
     *
     * @throws ApprovalDecisionAlreadyExistsException when the request already has a decision.
     */
    fun create(
        tenantId: TenantId,
        decision: ApprovalDecision,
    ): StoredApprovalDecision
}

/** Supplies unpredictable decision identities without coupling the use case to UUID generation. */
fun interface ApprovalDecisionIdentityGenerator {
    /** @return a fresh identity suitable for immutable approval history. */
    fun next(): ApprovalDecisionId
}

/** Identifies the immutable decision that already answered an approval request. */
class ApprovalDecisionAlreadyExistsException(
    val decisionId: UUID,
) : RuntimeException("approval request already has decision $decisionId")

/** Signals that an approval request can no longer accept a decision. */
class ApprovalRequestExpiredException(
    requestId: UUID,
) : RuntimeException("approval request $requestId is expired")

/** Signals that the authenticated actor lacks current evidence for the requested authority and scope. */
class CurrentApprovalAuthorityNotFoundException :
    RuntimeException("authenticated actor lacks current authority for this approval request")

/** Persistence ports consulted together while validating and recording a decision. */
data class ApprovalDecisionRecords(
    val requests: ApprovalRequestRepository,
    val runs: ResolutionRunRepository,
    val authorities: HumanAuthorityRepository,
    val decisions: ApprovalDecisionRepository,
)

/** Records authenticated human decisions without granting or executing a capability. */
class ApprovalDecisionService(
    private val records: ApprovalDecisionRecords,
    private val identities: ApprovalDecisionIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Records [outcome] for one current request using the actor's current authority evidence.
     *
     * [actorId] must come from an authenticated identity adapter, never from client input.
     * The request row remains locked from validation through insertion, making concurrent
     * responses deterministic. Both approval and rejection require the requested authority.
     *
     * @throws ApprovalRequestNotFoundException when [requestId] is absent from [tenantId].
     * @throws ApprovalRequestExpiredException when the request has reached its expiry.
     * @throws CurrentApprovalAuthorityNotFoundException when the actor lacks matching current evidence.
     * @throws ApprovalDecisionAlreadyExistsException when the request was already answered.
     */
    fun decide(
        tenantId: UUID,
        requestId: UUID,
        actorId: UUID,
        outcome: ApprovalDecisionOutcome,
    ): StoredApprovalDecision =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedRequestId = ApprovalRequestId(requestId)
            val storedRequest =
                records.requests.lockForDecision(scopedTenantId, scopedRequestId)
                    ?: throw ApprovalRequestNotFoundException(requestId)
            records.decisions.findIdByRequest(scopedTenantId, scopedRequestId)?.let {
                throw ApprovalDecisionAlreadyExistsException(it.value)
            }
            val now = clock.instant()
            if (storedRequest.request.statusAt(now) != ApprovalRequestStatus.PENDING) {
                throw ApprovalRequestExpiredException(requestId)
            }
            val storedRun =
                checkNotNull(records.runs.find(scopedTenantId, storedRequest.request.runId)) {
                    "approval request references an absent resolution run"
                }
            val evidenceScope =
                storedRun.run.caseId.takeIf { storedRequest.request.authority == ApprovalAuthority.REQUESTER }
            val evidence =
                records.authorities.findCurrent(
                    tenantId = scopedTenantId,
                    actorId = HumanActorId(actorId),
                    authority = storedRequest.request.authority,
                    caseId = evidenceScope,
                    at = now,
                ) ?: throw CurrentApprovalAuthorityNotFoundException()
            val decision =
                ApprovalDecision.record(
                    id = identities.next(),
                    basis =
                        ApprovalDecisionBasis(
                            request = storedRequest.request,
                            caseId = storedRun.run.caseId,
                            evidence = evidence.evidence,
                        ),
                    outcome = outcome,
                    decidedAt = now,
                )
            records.decisions.create(scopedTenantId, decision)
        }
}
