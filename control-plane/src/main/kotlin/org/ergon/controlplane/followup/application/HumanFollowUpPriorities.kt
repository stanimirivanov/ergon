package org.ergon.controlplane.followup.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.followup.domain.HumanFollowUpPriority
import org.ergon.followup.domain.HumanFollowUpPriorityRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exact original intent; the service validates revisions and adapters supply the authenticated actor. */
data class HumanFollowUpPriorityCommand(
    val tenantId: UUID,
    val workItemId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
    val expectedPriorityRevision: Long,
    val priority: HumanFollowUpPriority,
)

/** Historical assignment acknowledgement, not necessarily the current priority or renewed ownership. */
data class StoredHumanFollowUpPriority(
    val command: HumanFollowUpPriorityCommand,
    val revision: HumanFollowUpPriorityRevision,
    val assignedAt: Instant,
    val recordedAt: Instant,
)

data class HumanFollowUpPriorityRecording(
    val receipt: StoredHumanFollowUpPriority,
    val created: Boolean,
)

/** Caller holds the work-row lock in a short READ COMMITTED transaction and authorizes before lookup. */
interface HumanFollowUpPriorityRepository {
    /** Lookup uses tenant/work/command identity only; caller compares complete intent before disclosure. */
    fun find(command: HumanFollowUpPriorityCommand): StoredHumanFollowUpPriority?

    /** Returns zero before the first assignment; independent of ownership and case/run revisions. */
    fun currentRevision(
        tenant: TenantId,
        work: HumanFollowUpWorkItemId,
    ): HumanFollowUpPriorityRevision

    /** Appends the next assignment with accepted resolver evidence; constraint errors are defects, not replay. */
    fun record(
        command: HumanFollowUpPriorityCommand,
        evidenceId: ApprovalAuthorityEvidenceId,
        at: Instant,
    ): StoredHumanFollowUpPriority
}

class HumanFollowUpPriorityNotFoundException : RuntimeException("owned human follow-up is unavailable")

class HumanFollowUpPriorityConflictException : RuntimeException("priority intent conflicts; refresh owned work")

class InvalidHumanFollowUpPriorityException : RuntimeException("invalid priority or revision")

class HumanFollowUpPriorityUnavailableException : RuntimeException("human follow-up priority is not enabled")

/** Serializes administrative priority with ownership/completion; never rewrites upstream state. */
class HumanFollowUpPriorityService(
    private val claims: HumanFollowUpClaimRepository,
    private val priorities: HumanFollowUpPriorityRepository,
    private val authorities: HumanAuthorityRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Appends once or acknowledges identical intent. Both paths require current
     * authority and the original exact active ownership, sampled after lock waits.
     * Replay may acknowledge an older priority revision; it never reinstates it.
     *
     * @throws HumanFollowUpPriorityUnavailableException while rollout is disabled, including replay.
     * @throws InvalidHumanFollowUpPriorityException for nonpositive ownership or negative/exhausted priority revision.
     * @throws HumanFollowUpPriorityNotFoundException for absent/terminal/hidden work or lost authority.
     * @throws HumanFollowUpPriorityConflictException for stale owned coordinates or changed original intent.
     */
    fun assign(command: HumanFollowUpPriorityCommand): HumanFollowUpPriorityRecording {
        validate(command)
        return transactions.required {
            val tenant = TenantId(command.tenantId)
            val work = HumanFollowUpWorkItemId(command.workItemId)
            if (!claims.lockOpenWorkItem(tenant, work)) throw HumanFollowUpPriorityNotFoundException()
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val evidence = requireAuthority(command, now)
            requireOwned(command, now)
            priorities.find(command)?.let { prior -> return@required replay(command, prior) }
            if (priorities.currentRevision(tenant, work).value != command.expectedPriorityRevision) {
                throw HumanFollowUpPriorityConflictException()
            }
            HumanFollowUpPriorityRecording(priorities.record(command, evidence, now), created = true)
        }
    }

    private fun validate(command: HumanFollowUpPriorityCommand) {
        if (!enabled) throw HumanFollowUpPriorityUnavailableException()
        if (command.expectedOwnershipRevision <= 0 || command.expectedPriorityRevision < 0 ||
            command.expectedPriorityRevision == Long.MAX_VALUE
        ) {
            throw InvalidHumanFollowUpPriorityException()
        }
    }

    private fun requireAuthority(
        command: HumanFollowUpPriorityCommand,
        at: Instant,
    ): ApprovalAuthorityEvidenceId =
        authorities
            .findCurrent(
                TenantId(command.tenantId),
                HumanActorId(command.actorId),
                ApprovalAuthority.RESOLVER,
                null,
                at,
            )?.evidence
            ?.id ?: throw HumanFollowUpPriorityNotFoundException()

    private fun requireOwned(
        command: HumanFollowUpPriorityCommand,
        at: Instant,
    ) {
        val owned =
            claims.findOwnedWorkForResolver(
                TenantId(command.tenantId),
                HumanFollowUpWorkItemId(command.workItemId),
                HumanActorId(command.actorId),
                at,
            ) ?: throw HumanFollowUpPriorityNotFoundException()
        if (owned.claim.claim.id.value != command.claimId ||
            owned.ownershipRevision.value != command.expectedOwnershipRevision
        ) {
            throw HumanFollowUpPriorityConflictException()
        }
    }

    private fun replay(
        command: HumanFollowUpPriorityCommand,
        prior: StoredHumanFollowUpPriority,
    ): HumanFollowUpPriorityRecording {
        if (prior.command.actorId != command.actorId) throw HumanFollowUpPriorityNotFoundException()
        if (prior.command != command) throw HumanFollowUpPriorityConflictException()
        return HumanFollowUpPriorityRecording(prior, created = false)
    }
}
