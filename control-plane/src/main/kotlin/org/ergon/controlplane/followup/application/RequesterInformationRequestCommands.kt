package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.RequesterInformationQuestion
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Actor comes from the verified session; no recipient or timestamp is caller-selectable. */
data class RequesterInformationRequestCommand(
    val tenantId: UUID,
    val workItemId: UUID,
    val actorId: UUID,
    val requestId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val question: String,
)

/** Consumer-owned case gate, independent of case timelines and evidence disclosure. */
fun interface RequesterCaseWriteGuard {
    /**
     * Locks the OPEN explicitly requester-bound case until the caller's transaction ends.
     * Returns false for every unavailable target. Acquire the work lock before this lock.
     * Storage failures propagate; this does not establish human authority.
     */
    fun lockOpenBoundCase(
        tenant: TenantId,
        caseId: CaseId,
    ): Boolean
}

class RequesterInformationRequestNotFoundException : RuntimeException("Requester information request is unavailable")

class RequesterInformationRequestConflictException : RuntimeException("Request intent conflicts; refresh owned work")

class InvalidRequesterInformationRequestException :
    IllegalArgumentException(
        "expectedOwnershipRevision must be positive",
    )

/** Serializes question creation/recovery with work ownership and case closure; never delivers or executes work. */
class RequesterInformationRequestService(
    private val claims: HumanFollowUpClaimRepository,
    private val cases: RequesterCaseWriteGuard,
    private val requests: RequesterInformationRequestRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Records once or acknowledges exact persisted intent, under fresh access checks even for replay.
     * Server timestamps are not replay coordinates. Does not advance case/run/ownership revisions.
     * @throws RequesterInformationRequestNotFoundException for unavailable work/case or expired authority.
     * @throws RequesterInformationRequestConflictException for stale claim/revision or changed immutable intent.
     * @throws InvalidRequesterInformationRequestException for nonpositive expected revision.
     * @throws org.ergon.followup.domain.InvalidRequesterInformationQuestionException for invalid question text.
     */
    @Suppress("ThrowsCount") // Authority, target, fencing and intent retain distinct safe failures.
    fun record(command: RequesterInformationRequestCommand): RequesterInformationRequestRecording {
        if (command.expectedOwnershipRevision <= 0) throw InvalidRequesterInformationRequestException()
        val question = RequesterInformationQuestion(command.question)
        return transactions.required {
            val tenant = TenantId(command.tenantId)
            val work = HumanFollowUpWorkItemId(command.workItemId)
            val actor = HumanActorId(command.actorId)
            if (!claims.lockOpenWorkItem(tenant, work)) throw RequesterInformationRequestNotFoundException()
            val owned = requireOwner(tenant, work, actor)
            if (owned.claim.claim.id.value != command.claimId ||
                owned.ownershipRevision.value != command.expectedOwnershipRevision
            ) {
                throw RequesterInformationRequestConflictException()
            }
            val caseId = owned.workItem.item.caseId
            if (!cases.lockOpenBoundCase(tenant, caseId)) throw RequesterInformationRequestNotFoundException()
            // Case closure may have delayed lock acquisition beyond authority expiry.
            requireOwner(tenant, work, actor)
            val intent =
                RequesterInformationRequest(
                    RequesterInformationRequestId(command.requestId),
                    work,
                    caseId,
                    owned.claim.claim.id,
                    owned.ownershipRevision,
                    owned.claim.claim.resolverActorId,
                    question,
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                )
            val result = requests.record(tenant, intent)
            // A tenant-wide identity collision can wait behind another work item.
            // Authority must still be valid before any winner is acknowledged.
            requireOwner(tenant, work, actor)
            if (result.stored.request != intent.copy(requestedAt = result.stored.request.requestedAt)) {
                throw RequesterInformationRequestConflictException()
            }
            result
        }
    }

    private fun requireOwner(
        tenant: TenantId,
        work: HumanFollowUpWorkItemId,
        actor: HumanActorId,
    ) = claims.findOwnedWorkForResolver(tenant, work, actor, clock.instant())
        ?: throw RequesterInformationRequestNotFoundException()
}
