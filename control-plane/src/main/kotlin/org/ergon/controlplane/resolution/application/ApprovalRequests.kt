package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ApprovalRequestStatus
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Immutable approval request paired with the database instant at which it became durable. */
data class StoredApprovalRequest(
    val request: ApprovalRequest,
    val recordedAt: Instant,
)

/** Approval request plus its status at one application-clock instant. */
data class ApprovalRequestView(
    val stored: StoredApprovalRequest,
    val status: ApprovalRequestStatus,
)

/** Durable, tenant-scoped history of immutable approval requests. */
interface ApprovalRequestRepository {
    /**
     * Stores [request] after serializing creation for its run.
     *
     * An expired request remains in history and may be followed by a replacement.
     * A request whose expiry is after [ApprovalRequest.requestedAt] is still
     * active and must prevent insertion.
     *
     * @throws ActiveApprovalRequestExistsException when the run already has an active request.
     * @throws ResolutionRunNotFoundException when the run is absent from [tenantId].
     */
    fun create(
        tenantId: TenantId,
        request: ApprovalRequest,
    ): StoredApprovalRequest

    /** @return the tenant-scoped request, or `null` when it is absent. */
    fun find(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): StoredApprovalRequest?

    /**
     * Retrieves and locks one request for a decision in the caller's transaction.
     *
     * The lock must be retained through decision insertion so concurrent responses
     * cannot both observe an undecided request.
     *
     * @return the tenant-scoped request, or `null` when it is absent.
     */
    fun lockForDecision(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): StoredApprovalRequest?
}

/** Supplies unpredictable approval-request identities without coupling the use case to UUID generation. */
fun interface ApprovalRequestIdentityGenerator {
    /** @return a fresh identity suitable for durable approval history. */
    fun next(): ApprovalRequestId
}

/** Signals that a run has no human-approval prerequisite to request. */
class ApprovalNotRequiredException(
    runId: UUID,
) : RuntimeException("resolution run $runId does not require human approval")

/** Identifies the still-valid request that prevents a concurrent or duplicate request. */
class ActiveApprovalRequestExistsException(
    val requestId: UUID,
    val expiresAt: Instant,
) : RuntimeException("approval request $requestId remains active until $expiresAt")

/** Signals tenant-scoped request absence without revealing another tenant's data. */
class ApprovalRequestNotFoundException(
    requestId: UUID,
) : RuntimeException("approval request $requestId was not found")

/** Creates bounded approval requests and reports their clock-derived status. */
class ApprovalRequestService(
    private val runs: ResolutionRunRepository,
    private val requests: ApprovalRequestRepository,
    private val identities: ApprovalRequestIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val lifetime: Duration,
) {
    init {
        require(!lifetime.isZero && !lifetime.isNegative && lifetime <= ApprovalRequest.MAX_LIFETIME) {
            "approval request lifetime must be positive and not exceed ${ApprovalRequest.MAX_LIFETIME}"
        }
    }

    /**
     * Creates a request for the human authority pinned by [runId].
     *
     * The resulting request is not an approval and contains no actor identity.
     * Once expired, it remains auditable and a later call may create a replacement.
     *
     * @throws ResolutionRunNotFoundException when [runId] is absent from the tenant boundary.
     * @throws ApprovalNotRequiredException when the run requires no human approval.
     * @throws ActiveApprovalRequestExistsException when a request remains active.
     */
    fun request(
        tenantId: UUID,
        runId: UUID,
    ): ApprovalRequestView =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val storedRun =
                runs.find(scopedTenantId, ResolutionRunId(runId))
                    ?: throw ResolutionRunNotFoundException(runId)
            val run = storedRun.run
            if (
                run.initialState != ResolutionRunInitialState.WAITING_FOR_APPROVAL ||
                run.requiredApproval == ApprovalRequirement.NONE
            ) {
                throw ApprovalNotRequiredException(runId)
            }
            val now = clock.instant()
            val request = ApprovalRequest.create(identities.next(), run, now, lifetime)
            requests.create(scopedTenantId, request).toView(now)
        }

    /**
     * Returns one request with status evaluated at the current application time.
     *
     * @throws ApprovalRequestNotFoundException when [requestId] is absent from [tenantId].
     */
    fun get(
        tenantId: UUID,
        requestId: UUID,
    ): ApprovalRequestView {
        val stored =
            requests.find(TenantId(tenantId), ApprovalRequestId(requestId))
                ?: throw ApprovalRequestNotFoundException(requestId)
        return stored.toView(clock.instant())
    }
}

private fun StoredApprovalRequest.toView(now: Instant) = ApprovalRequestView(this, request.statusAt(now))
