package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.followup.domain.RequesterInformationAnswer
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.followup.domain.RequesterInformationResponseId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Actor is resolved from the verified requester session; timestamp and attribution are server-owned. */
data class RequesterInformationResponseCommand(
    val tenantId: UUID,
    val caseId: UUID,
    val requestId: UUID,
    val actorId: UUID,
    val responseId: UUID,
    val answer: String,
)

/** Consumer-owned gate; question possession alone cannot authorize answering. */
fun interface RequesterAnswerWriteGuard {
    /**
     * Locks the OPEN case owned by [actor] and verifies [request] belongs to it.
     * Returns false for every unavailable target, including closure after waiting for its lock.
     * Caller owns the transaction. Acquire no work/run lock after this gate; storage failures propagate.
     */
    fun lockQuestion(
        tenant: TenantId,
        case: CaseId,
        request: RequesterInformationRequestId,
        actor: HumanActorId,
    ): Boolean
}

class RequesterInformationResponseNotFoundException : RuntimeException("Requester question is unavailable")

class RequesterInformationResponseConflictException : RuntimeException("Answer intent conflicts")

/** Case-serialized answering and recovery, independent of resolver authority and work lifecycle. */
class RequesterInformationResponseService(
    private val cases: RequesterAnswerWriteGuard,
    private val responses: RequesterInformationResponseRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Records once or acknowledges exact response UUID, question and text under fresh requester access.
     * Holds the case lock through unique-key waits and comparison; conflicting writes roll back.
     * Server timestamps are not retry coordinates. No case/run/ownership revision or proof changes.
     * @throws RequesterInformationResponseNotFoundException for every inaccessible, missing or closed target.
     * @throws RequesterInformationResponseConflictException when the winning answer has different intent.
     * @throws org.ergon.followup.domain.InvalidRequesterInformationAnswerException for invalid text.
     */
    fun record(command: RequesterInformationResponseCommand): RequesterInformationResponseRecording {
        val answer = RequesterInformationAnswer(command.answer)
        return transactions.required {
            val tenant = TenantId(command.tenantId)
            val case = CaseId(command.caseId)
            val request = RequesterInformationRequestId(command.requestId)
            val actor = HumanActorId(command.actorId)
            if (!cases.lockQuestion(tenant, case, request, actor)) throw RequesterInformationResponseNotFoundException()
            val intent =
                RequesterInformationResponse(
                    RequesterInformationResponseId(command.responseId),
                    request,
                    answer,
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                )
            val result = responses.record(tenant, intent)
            val stored = result.stored
            if (stored.caseId != case || stored.requesterActorId != actor ||
                stored.response != intent.copy(respondedAt = stored.response.respondedAt)
            ) {
                // A tenant-wide UUID may belong to another case. Never return its payload on conflict.
                throw RequesterInformationResponseConflictException()
            }
            result
        }
    }
}
