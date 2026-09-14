package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequestStatus
import org.ergon.resolution.domain.CapabilityAuthorizationGrant
import org.ergon.resolution.domain.CapabilityAuthorizationGrantBasis
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Immutable capability authorization paired with the database instant at which it became durable. */
data class StoredCapabilityAuthorizationGrant(
    val grant: CapabilityAuthorizationGrant,
    val recordedAt: Instant,
)

/** Durable tenant-scoped history of narrowly scoped capability authorizations. */
interface CapabilityAuthorizationGrantRepository {
    /** @return the grant already derived from [decisionId], or `null` when none exists. */
    fun findIdByDecision(
        tenantId: TenantId,
        decisionId: ApprovalDecisionId,
    ): CapabilityAuthorizationGrantId?

    /**
     * Persists [grant] once for its approval decision.
     *
     * The caller must hold the source decision lock through this operation.
     *
     * @throws CapabilityAuthorizationGrantAlreadyExistsException when the decision already produced a grant.
     */
    fun create(
        tenantId: TenantId,
        grant: CapabilityAuthorizationGrant,
    ): StoredCapabilityAuthorizationGrant
}

/** Supplies unpredictable grant identities without coupling the use case to UUID generation. */
fun interface CapabilityAuthorizationGrantIdentityGenerator {
    /** @return a fresh identity suitable for an immutable authorization record. */
    fun next(): CapabilityAuthorizationGrantId
}

/** Signals tenant-scoped decision absence without revealing another tenant's data. */
class ApprovalDecisionNotFoundException(
    decisionId: UUID,
) : RuntimeException("approval decision $decisionId was not found")

/** Signals that a rejection cannot become capability authorization. */
class ApprovalDecisionNotApprovedException(
    decisionId: UUID,
) : RuntimeException("approval decision $decisionId is not approved")

/** Signals that the approval request's bounded authorization window has ended. */
class CapabilityAuthorizationWindowExpiredException(
    decisionId: UUID,
) : RuntimeException("approval decision $decisionId can no longer produce an authorization grant")

/** Identifies the immutable grant already derived from an approval decision. */
class CapabilityAuthorizationGrantAlreadyExistsException(
    val grantId: UUID,
) : RuntimeException("approval decision already produced authorization grant $grantId")

/** Sources consulted together while deriving a grant from immutable approval history. */
data class CapabilityAuthorizationGrantRecords(
    val decisions: ApprovalDecisionRepository,
    val requests: ApprovalRequestRepository,
    val runs: ResolutionRunRepository,
    val grants: CapabilityAuthorizationGrantRepository,
)

/** Derives bounded capability authorization without consuming it or invoking a connector. */
class CapabilityAuthorizationGrantService(
    private val records: CapabilityAuthorizationGrantRecords,
    private val identities: CapabilityAuthorizationGrantIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Creates one grant for the exact run scope approved by [decisionId].
     *
     * The decision is locked through insertion. The originating request must
     * remain current at the single application-clock instant used for derivation;
     * its expiry becomes the grant expiry. The result is only eligible for later
     * single-use consumption and does not invoke the capability.
     *
     * @throws ApprovalDecisionNotFoundException when [decisionId] is absent from [tenantId].
     * @throws ApprovalDecisionNotApprovedException when the decision rejected the request.
     * @throws CapabilityAuthorizationWindowExpiredException when the approval request has expired.
     * @throws CapabilityAuthorizationGrantAlreadyExistsException when a grant was already derived.
     */
    fun authorize(
        tenantId: UUID,
        decisionId: UUID,
    ): StoredCapabilityAuthorizationGrant =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedDecisionId = ApprovalDecisionId(decisionId)
            val storedDecision =
                records.decisions.lockForAuthorization(scopedTenantId, scopedDecisionId)
                    ?: throw ApprovalDecisionNotFoundException(decisionId)
            records.grants.findIdByDecision(scopedTenantId, scopedDecisionId)?.let {
                throw CapabilityAuthorizationGrantAlreadyExistsException(it.value)
            }
            val decision = storedDecision.decision
            if (decision.outcome != ApprovalDecisionOutcome.APPROVED) {
                throw ApprovalDecisionNotApprovedException(decisionId)
            }
            val request =
                checkNotNull(records.requests.find(scopedTenantId, decision.requestId)) {
                    "approval decision references an absent approval request"
                }.request
            val run =
                checkNotNull(records.runs.find(scopedTenantId, decision.runId)) {
                    "approval decision references an absent resolution run"
                }.run
            val now = clock.instant()
            if (request.statusAt(now) != ApprovalRequestStatus.PENDING) {
                throw CapabilityAuthorizationWindowExpiredException(decisionId)
            }
            val grant =
                try {
                    CapabilityAuthorizationGrant.derive(
                        id = identities.next(),
                        basis = CapabilityAuthorizationGrantBasis(run, request, decision),
                        authorizedAt = now,
                    )
                } catch (exception: IllegalArgumentException) {
                    // Source rows are immutable and FK-linked, so mismatch means durable corruption.
                    throw IllegalStateException("stored authorization grant sources are inconsistent", exception)
                }
            records.grants.create(scopedTenantId, grant)
        }
}
