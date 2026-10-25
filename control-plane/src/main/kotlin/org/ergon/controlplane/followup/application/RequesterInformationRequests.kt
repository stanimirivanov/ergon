package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.RequesterInformationQuestion
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant

/** Immutable question and CLAIMED-event attribution; recipient is the case's immutable binding. */
data class RequesterInformationRequest(
    val requestId: RequesterInformationRequestId,
    val workItemId: HumanFollowUpWorkItemId,
    val caseId: CaseId,
    val claimId: HumanFollowUpClaimId,
    val ownershipRevision: HumanFollowUpOwnershipRevision,
    val authorActorId: HumanActorId,
    val question: RequesterInformationQuestion,
    val requestedAt: Instant,
)

data class StoredRequesterInformationRequest(
    val request: RequesterInformationRequest,
    val recordedAt: Instant,
)

data class RequesterInformationRequestRecording(
    val stored: StoredRequesterInformationRequest,
    val created: Boolean,
)

/** Tenant request identity is global across work items, not an authorization token. */
fun interface RequesterInformationRequestRepository {
    /**
     * Inserts once or returns the winning immutable row after a competing commit.
     * Requires a READ COMMITTED transaction; does not check current ownership or access.
     * Author/claim are derived from the historical event, not trusted from input.
     * Caller must compare the returned intent and authorize access before disclosure.
     * Storage failures propagate; they must not become a false acknowledgement.
     */
    fun record(
        tenant: TenantId,
        request: RequesterInformationRequest,
    ): RequesterInformationRequestRecording
}
