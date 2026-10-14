package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Clock
import java.util.UUID

/**
 * Transfers browser supervision, never execution permission or follow-up ownership.
 *
 * The run lock serializes first assignment and handover. The state lock follows
 * it so a terminal decision cannot race a new transfer. No remote I/O enters
 * either transaction. Replay is private to the originating, currently authorized
 * actor and never re-applies a past command after ownership has changed again.
 */
@Suppress("LongParameterList") // Distinct application ports keep identity, audit, and state responsibilities explicit.
class RunSupervisorHandoverService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val handovers: RunSupervisorHandoverRepository,
    private val candidates: RunSupervisorCandidateReader,
    private val authorities: HumanAuthorityRepository,
    private val states: ResolutionRunTransitionRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    val enabled: Boolean,
) {
    /**
     * Returns eligible recipient IDs after fresh current-supervisor and epoch checks.
     * Eligibility is checked again when the command executes, not inferred from this list.
     * [limit] is 1..100; [afterActorId] is an exclusive private UUID cursor.
     * The bounded page contains no display labels or provider subjects.
     * @throws AssignedRunNotFoundException for unavailable or unauthorized supervision.
     * @throws RunSupervisorHandoverConflictException for a stale epoch.
     * @throws RunSupervisorHandoverDisabledException while the rollout switch is off.
     */
    fun listCandidates(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
        expectedRevision: RunSupervisionRevision,
        limit: Int,
        afterActorId: UUID?,
    ): RunSupervisorCandidatePage =
        transactions.required {
            require(limit in 1..MAX_RUN_SUPERVISOR_CANDIDATE_PAGE_SIZE) { "candidate page size must be in 1..100" }
            if (!enabled) throw RunSupervisorHandoverDisabledException()
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val actor = HumanActorId(actorId)
            val now = clock.instant()
            val current = assignments.findAssigned(tenant, run, actor, now) ?: throw AssignedRunNotFoundException()
            if (current.revision != expectedRevision) throw RunSupervisorHandoverConflictException()
            val state = states.findState(tenant, run) ?: error("assigned run state is absent")
            if (!canHandover(state.state) || current.revision.value == MAX_RUN_SUPERVISION_REVISION) {
                throw RunSupervisorHandoverUnavailableException()
            }
            val rows = candidates.listCandidates(tenant, actor, now, limit + 1, afterActorId)
            val page = rows.take(limit)
            RunSupervisorCandidatePage(page, if (rows.size > limit) page.last().value else null, current.revision)
        }

    /**
     * Applies an exact-revision transfer or returns its original durable receipt.
     *
     * @throws AssignedRunNotFoundException for absent runs, non-owners, foreign
     *   command receipts, or expired outgoing authority, without exposing an incumbent.
     * @throws RunSupervisorNotEligibleException when the recipient is not another current tenant resolver.
     * @throws RunSupervisorHandoverConflictException for stale epochs or changed replay intent.
     * @throws RunSupervisorHandoverUnavailableException for terminal runs or exhausted epochs.
     * @throws RunSupervisorHandoverDisabledException while transfers are disabled.
     */
    fun handover(command: HandoverRunSupervisorCommand): RunSupervisorHandoverRecording =
        transactions.required {
            if (!enabled) throw RunSupervisorHandoverDisabledException()
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            val now = clock.instant()
            val outgoing =
                authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, now)
                    ?: throw AssignedRunNotFoundException()
            handovers.lockCommand(tenant, command.commandId)
            handovers.findByCommandId(tenant, command.commandId)?.let { stored ->
                return@required replay(command, run, actor, stored)
            }
            val current = assignments.findAssigned(tenant, run, actor, now) ?: throw AssignedRunNotFoundException()
            if (current.revision != command.expectedRevision) throw RunSupervisorHandoverConflictException()
            val state = states.lockState(tenant, run) ?: error("assigned run state is absent")
            if (!canHandover(state.state) || current.revision.value == MAX_RUN_SUPERVISION_REVISION) {
                throw RunSupervisorHandoverUnavailableException()
            }
            val recipient = HumanActorId(command.supervisorActorId)
            if (recipient == actor) throw RunSupervisorNotEligibleException()
            val incoming =
                authorities.findCurrent(tenant, recipient, ApprovalAuthority.RESOLVER, null, now)
                    ?: throw RunSupervisorNotEligibleException()
            val fact =
                RunSupervisorHandover(
                    run,
                    current.initialAssignment.assignment.id,
                    command.commandId,
                    command.expectedRevision.next(),
                    actor,
                    recipient,
                    outgoing.evidence.id,
                    incoming.evidence.id,
                    now,
                )
            RunSupervisorHandoverRecording(handovers.record(tenant, fact), created = true)
        }

    private fun replay(
        command: HandoverRunSupervisorCommand,
        run: ResolutionRunId,
        actor: HumanActorId,
        stored: StoredRunSupervisorHandover,
    ): RunSupervisorHandoverRecording {
        val fact = stored.handover
        // A foreign command must not expose its recipient, epoch or prior actor.
        if (fact.previousActorId != actor || fact.runId != run) throw AssignedRunNotFoundException()
        if (fact.supervisorActorId.value != command.supervisorActorId ||
            fact.revision != command.expectedRevision.next()
        ) {
            throw RunSupervisorHandoverConflictException()
        }
        return RunSupervisorHandoverRecording(stored, created = false)
    }
}

/** Active supervision states; this does not grant authority to mutate runtime state. */
internal fun canHandover(state: ResolutionRunState): Boolean =
    when (state) {
        ResolutionRunState.WAITING_FOR_APPROVAL,
        ResolutionRunState.READY_FOR_AUTHORIZATION,
        ResolutionRunState.VERIFYING,
        ResolutionRunState.ACTION_FAILED,
        -> true

        ResolutionRunState.VERIFIED_RESOLVED,
        ResolutionRunState.SUPERSEDED,
        ResolutionRunState.ESCALATED,
        -> false
    }
