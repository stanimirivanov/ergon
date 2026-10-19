package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Coordinates browser sessions, not runtime workers, provider permission or human approval. */
@Suppress("LongParameterList") // Run serialization, authority, persistence, time and identity remain explicit.
class RunControlService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val states: ResolutionRunTransitionRepository,
    private val controls: RunControlRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val identities: RunControlIdentityGenerator,
    private val enabled: Boolean,
) {
    /**
     * Observes only the current supervisor's run. Another session's binding and fence remain private.
     * Reading does not acquire, renew, or make terminal work active.
     * @throws AssignedRunNotFoundException for absent or unauthorized supervision.
     */
    fun status(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
        holderId: UUID,
    ): RunControlView =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            val state = checkNotNull(states.lockState(tenant, run))
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val current =
                assignments.findAssigned(tenant, run, HumanActorId(actorId), now)
                    ?: throw AssignedRunNotFoundException()
            val lease = controls.current(tenant, run)
            val status =
                when {
                    !enabled -> RunControlStatus.DISABLED

                    !canHandover(state.state) -> RunControlStatus.TERMINAL

                    lease == null || lease.command.supervisionRevision != current.revision ||
                        lease.command.action == RunControlAction.RELEASE -> RunControlStatus.AVAILABLE

                    !lease.activeAt(now) -> RunControlStatus.EXPIRED

                    lease.holderId == holderId -> RunControlStatus.THIS_SESSION

                    else -> RunControlStatus.ANOTHER_SESSION
                }
            RunControlView(
                status,
                current.revision,
                now,
                lease?.fence.takeIf { status == RunControlStatus.THIS_SESSION },
                lease?.expiresAt.takeIf {
                    status in
                        setOf(RunControlStatus.THIS_SESSION, RunControlStatus.ANOTHER_SESSION, RunControlStatus.EXPIRED)
                },
            )
        }

    /**
     * Applies one 120-second lease operation or returns its exact historical result without restoring control.
     * Only acquisition advances the fence; renewal preserves it and never happens implicitly on a command/read.
     * [holderId] must originate in the authenticated server session, not request JSON.
     * @throws AssignedRunNotFoundException for foreign receipts or missing current resolver authority.
     * @throws RunControlConflictException for stale epoch/fence, terminal state, another holder, or changed replay
     * intent.
     * @throws RunControlDisabledException for a new lifecycle command while rollout is off; exact history remains
     * replayable.
     */
    fun change(
        command: RunControlCommand,
        holderId: UUID,
    ): StoredRunControl =
        transactions.required {
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            controls.lockCommand(tenant, command.commandId)
            requireResolver(tenant, actor)
            controls.findCommand(tenant, command.commandId)?.let {
                if (it.command.runId != command.runId || it.command.actorId != command.actorId) {
                    throw AssignedRunNotFoundException()
                }
                if (it.command != command) throw RunControlConflictException()
                return@required it
            }
            if (!enabled) throw RunControlDisabledException()
            val state = checkNotNull(states.lockState(tenant, run))
            val previous = controls.current(tenant, run)
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            if (previous != null && now.isBefore(previous.changedAt)) throw RunControlConflictException()
            val current = assignments.findAssigned(tenant, run, actor, now) ?: throw AssignedRunNotFoundException()
            if (current.revision != command.supervisionRevision || !canHandover(state.state)) {
                throw RunControlConflictException()
            }
            val fence = fenceFor(command, holderId, previous, now)
            val expires =
                if (command.action == RunControlAction.RELEASE) {
                    checkNotNull(previous).expiresAt
                } else {
                    now.plusSeconds(RUN_CONTROL_LEASE_SECONDS)
                }
            controls.append(
                StoredRunControl(
                    command,
                    (previous?.sequence ?: 0) + 1,
                    fence,
                    holderId,
                    now,
                    expires,
                    now,
                    current.supervisedAt,
                ),
            )
        }

    private fun fenceFor(
        command: RunControlCommand,
        holderId: UUID,
        previous: StoredRunControl?,
        at: Instant,
    ): RunControlFence {
        val active =
            previous?.command?.supervisionRevision == command.supervisionRevision &&
                previous?.activeAt(at) == true
        val previousFence = previous?.fence
        if (command.action == RunControlAction.ACQUIRE) {
            if (active || previousFence?.revision == MAX_RUN_SUPERVISION_REVISION) throw RunControlConflictException()
            return RunControlFence(identities.next(), (previousFence?.revision ?: 0) + 1)
        }
        return previous
            ?.takeIf {
                active && it.holderId == holderId && it.fence == command.expectedFence
            }?.fence ?: throw RunControlConflictException()
    }

    private fun requireResolver(
        tenant: TenantId,
        actor: HumanActorId,
    ) {
        if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant()) == null) {
            throw AssignedRunNotFoundException()
        }
    }
}
