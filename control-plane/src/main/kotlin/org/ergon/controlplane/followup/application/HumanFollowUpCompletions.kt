package org.ergon.controlplane.followup.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exact administrative intent; authentication supplies [actorId], never the request body. */
data class HumanFollowUpCompletionCommand(
    val tenantId: UUID,
    val workItemId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val claimId: UUID,
    val expectedOwnershipRevision: Long,
)

/** Immutable historical acknowledgement, not case resolution or renewed private-work access. */
data class StoredHumanFollowUpCompletion(
    val command: HumanFollowUpCompletionCommand,
    val completedAt: Instant,
    val recordedAt: Instant,
)

data class HumanFollowUpCompletionRecording(
    val receipt: StoredHumanFollowUpCompletion,
    val created: Boolean,
)

/** Terminal audit boundary used inside the existing work-row-locked application transaction. */
interface HumanFollowUpCompletionRepository {
    /** Historical lookup includes terminal work; callers must authorize disclosure and compare the entire intent. */
    fun find(command: HumanFollowUpCompletionCommand): StoredHumanFollowUpCompletion?

    /** Appends one terminal fact with accepted authority; never changes ownership, case, run or proof. */
    fun record(
        command: HumanFollowUpCompletionCommand,
        evidenceId: ApprovalAuthorityEvidenceId,
        at: Instant,
    ): StoredHumanFollowUpCompletion
}

class HumanFollowUpCompletionNotFoundException : RuntimeException("owned human follow-up is unavailable")

class HumanFollowUpCompletionConflictException : RuntimeException("completion intent conflicts; refresh owned work")

class InvalidHumanFollowUpCompletionException : RuntimeException("completion requires a positive ownership revision")

class HumanFollowUpCompletionUnavailableException : RuntimeException("human follow-up completion is not enabled")

/** Completes current-owned work, with separately authorized recovery after active access has ended. */
class HumanFollowUpCompletionService(
    private val claims: HumanFollowUpClaimRepository,
    private val completions: HumanFollowUpCompletionRepository,
    private val authorities: HumanAuthorityRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Appends once or acknowledges identical original intent under current resolver authority.
     * Time and authority are sampled after the lock wait, including for terminal replay.
     * No remote call occurs and no ownership/lifecycle projection is rewritten.
     *
     * @throws HumanFollowUpCompletionUnavailableException while rollout is disabled, including recovery.
     * @throws InvalidHumanFollowUpCompletionException for a nonpositive revision.
     * @throws HumanFollowUpCompletionNotFoundException for hidden/absent work, another actor or lost authority.
     * @throws HumanFollowUpCompletionConflictException for stale ownership or changed same-actor command intent.
     */
    fun complete(command: HumanFollowUpCompletionCommand): HumanFollowUpCompletionRecording {
        validate(command)
        return transactions.required {
            val tenant = TenantId(command.tenantId)
            val work = HumanFollowUpWorkItemId(command.workItemId)
            // A terminal opening row is still locked even though this active-state check returns false.
            val open = claims.lockOpenWorkItem(tenant, work)
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val authority =
                authorities.findCurrent(tenant, HumanActorId(command.actorId), ApprovalAuthority.RESOLVER, null, now)
                    ?: throw HumanFollowUpCompletionNotFoundException()
            completions.find(command)?.let { prior -> return@required replay(command, prior) }
            if (!open) throw HumanFollowUpCompletionNotFoundException()
            requireOwned(command, now)
            HumanFollowUpCompletionRecording(completions.record(command, authority.evidence.id, now), created = true)
        }
    }

    private fun validate(command: HumanFollowUpCompletionCommand) {
        if (!enabled) throw HumanFollowUpCompletionUnavailableException()
        if (command.expectedOwnershipRevision <= 0) throw InvalidHumanFollowUpCompletionException()
    }

    private fun replay(
        command: HumanFollowUpCompletionCommand,
        prior: StoredHumanFollowUpCompletion,
    ): HumanFollowUpCompletionRecording {
        if (prior.command.actorId != command.actorId) throw HumanFollowUpCompletionNotFoundException()
        if (prior.command != command) throw HumanFollowUpCompletionConflictException()
        return HumanFollowUpCompletionRecording(prior, created = false)
    }

    private fun requireOwned(
        command: HumanFollowUpCompletionCommand,
        at: Instant,
    ) {
        val owned =
            claims.findOwnedWorkForResolver(
                TenantId(command.tenantId),
                HumanFollowUpWorkItemId(command.workItemId),
                HumanActorId(command.actorId),
                at,
            ) ?: throw HumanFollowUpCompletionNotFoundException()
        if (owned.claim.claim.id.value != command.claimId ||
            owned.ownershipRevision.value != command.expectedOwnershipRevision
        ) {
            throw HumanFollowUpCompletionConflictException()
        }
    }
}
