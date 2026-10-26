package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.followup.domain.RequesterInformationAnswer
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.followup.domain.RequesterInformationResponseId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant

/** Immutable answer intent; actor and case are derived from the question's explicit requester binding. */
data class RequesterInformationResponse(
    val responseId: RequesterInformationResponseId,
    val requestId: RequesterInformationRequestId,
    val answer: RequesterInformationAnswer,
    val respondedAt: Instant,
)

/** Database winner with historical attribution and database precision, not continuing access. */
data class StoredRequesterInformationResponse(
    val response: RequesterInformationResponse,
    val caseId: CaseId,
    val requesterActorId: HumanActorId,
    val recordedAt: Instant,
)

data class RequesterInformationResponseRecording(
    val stored: StoredRequesterInformationResponse,
    val created: Boolean,
)

/** Storage boundary for the forthcoming authenticated response command; no current-access decision. */
fun interface RequesterInformationResponseRepository {
    /**
     * Inserts one answer per question, or returns a whole immutable winner after a competing commit.
     * Requires a caller-owned READ COMMITTED transaction. A response-ID collision takes precedence
     * over a question collision when they identify different rows; no losing values are merged.
     * The caller must authorize access and compare every returned intent coordinate before disclosure.
     * Does not check OPEN case status or authenticated actor. Storage failures propagate.
     */
    fun record(
        tenant: TenantId,
        response: RequesterInformationResponse,
    ): RequesterInformationResponseRecording
}
