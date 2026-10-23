package org.ergon.controlplane.followup.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exact transfer intent. The inbound authentication adapter, never the browser, supplies [actorId]. */
data class HumanFollowUpHandoverCommand(
    val tenantId: UUID,
    val workItemId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val recipientActorId: UUID,
)

/** Historical acknowledgement, not evidence that either actor still owns the work. */
data class StoredHumanFollowUpHandover(
    val command: HumanFollowUpHandoverCommand,
    val successorClaimId: HumanFollowUpClaimId,
    val ownershipRevision: HumanFollowUpOwnershipRevision,
    val handedOverAt: Instant,
    val recordedAt: Instant,
)

data class HumanFollowUpHandoverRecording(
    val receipt: StoredHumanFollowUpHandover,
    val created: Boolean,
)

/** Called under the existing work-item lock and application transaction; no remote I/O. */
interface HumanFollowUpHandoverRepository {
    /** Unfiltered historical lookup: the service must gate both disclosure and exact replay. */
    fun find(command: HumanFollowUpHandoverCommand): StoredHumanFollowUpHandover?

    /**
     * Appends the successor claim, replaces ownership and stores the receipt atomically.
     * [successor] carries the recipient's currently accepted authority, not the sender's.
     * A failed expected-state update must roll back all three writes.
     */
    fun record(
        command: HumanFollowUpHandoverCommand,
        successor: HumanFollowUpClaim,
        senderEvidenceId: ApprovalAuthorityEvidenceId,
    ): StoredHumanFollowUpHandover
}

class HumanFollowUpHandoverNotFoundException : RuntimeException("owned human follow-up is unavailable")

class HumanFollowUpHandoverConflictException : RuntimeException("handover intent conflicts; refresh owned work")

class HumanFollowUpHandoverRecipientUnavailableException : RuntimeException("handover recipient is unavailable")

class InvalidHumanFollowUpHandoverException : RuntimeException("invalid handover recipient or claim revision")

class HumanFollowUpHandoverUnavailableException : RuntimeException("human follow-up handover is not enabled")

/** Transfers only current-owned OPEN work; acknowledgement never grants renewed access. */
@Suppress("LongParameterList") // Independent authority, ownership, receipt and transaction ports remain visible.
class HumanFollowUpHandoverService(
    private val claims: HumanFollowUpClaimRepository,
    private val handovers: HumanFollowUpHandoverRepository,
    private val authorities: HumanAuthorityRepository,
    private val identities: HumanFollowUpClaimIdentityGenerator,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Applies one exact handover or returns only its original receipt to its authorized sender.
     *
     * Both authorities are checked after the work-item lock wait on a first transfer.
     * Replay requires current sender authority but not current ownership or recipient authority:
     * it acknowledges a past transfer and cannot read successor-private context.
     *
     * @throws HumanFollowUpHandoverUnavailableException while rollout is disabled.
     * @throws InvalidHumanFollowUpHandoverException for self-transfer or an unsafe revision.
     * @throws HumanFollowUpHandoverNotFoundException for absent/closed/hidden work or lost sender authority.
     * @throws HumanFollowUpHandoverConflictException for stale coordinates or changed same-sender intent.
     * @throws HumanFollowUpHandoverRecipientUnavailableException for every ineligible recipient.
     */
    fun handOver(command: HumanFollowUpHandoverCommand): HumanFollowUpHandoverRecording {
        validate(command)
        return transactions.required {
            val tenant = TenantId(command.tenantId)
            val work = HumanFollowUpWorkItemId(command.workItemId)
            if (!claims.lockOpenWorkItem(tenant, work)) throw HumanFollowUpHandoverNotFoundException()
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val sender =
                authorities.findCurrent(tenant, HumanActorId(command.actorId), ApprovalAuthority.RESOLVER, null, now)
                    ?: throw HumanFollowUpHandoverNotFoundException()
            handovers.find(command)?.let { prior -> return@required replay(command, prior) }
            requireOwned(command, now)
            val successor = successorClaim(command, now)
            HumanFollowUpHandoverRecording(handovers.record(command, successor, sender.evidence.id), created = true)
        }
    }

    private fun requireOwned(
        command: HumanFollowUpHandoverCommand,
        at: Instant,
    ) {
        val owned =
            claims.findOwnedWorkForResolver(
                TenantId(command.tenantId),
                HumanFollowUpWorkItemId(command.workItemId),
                HumanActorId(command.actorId),
                at,
            ) ?: throw HumanFollowUpHandoverNotFoundException()
        if (owned.claim.claim.id.value != command.claimId ||
            owned.ownershipRevision.value != command.expectedOwnershipRevision
        ) {
            throw HumanFollowUpHandoverConflictException()
        }
    }

    private fun successorClaim(
        command: HumanFollowUpHandoverCommand,
        at: Instant,
    ): HumanFollowUpClaim {
        val recipient =
            authorities.findCurrent(
                TenantId(command.tenantId),
                HumanActorId(command.recipientActorId),
                ApprovalAuthority.RESOLVER,
                null,
                at,
            ) ?: throw HumanFollowUpHandoverRecipientUnavailableException()
        return HumanFollowUpClaim.claim(
            identities.next(),
            HumanFollowUpWorkItemId(command.workItemId),
            recipient.evidence,
            at,
        )
    }

    private fun validate(command: HumanFollowUpHandoverCommand) {
        if (!enabled) throw HumanFollowUpHandoverUnavailableException()
        if (command.actorId == command.recipientActorId ||
            command.expectedOwnershipRevision <= 0 ||
            command.expectedOwnershipRevision > Long.MAX_VALUE - 2
        ) {
            throw InvalidHumanFollowUpHandoverException()
        }
    }

    private fun replay(
        command: HumanFollowUpHandoverCommand,
        prior: StoredHumanFollowUpHandover,
    ): HumanFollowUpHandoverRecording {
        if (prior.command.actorId != command.actorId) throw HumanFollowUpHandoverNotFoundException()
        if (prior.command != command) throw HumanFollowUpHandoverConflictException()
        return HumanFollowUpHandoverRecording(prior, created = false)
    }
}
