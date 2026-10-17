package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock

/**
 * Confirms a fresh successor, not recovery of an uncertain invocation. No connector enters this transaction.
 *
 * Lock order is predecessor run, tenant command, predecessor state, then case through the existing retry service.
 * A new uncommitted successor cannot have competing supervision. Internal retry writers take state/case locks
 * without upgrading the predecessor run key lock, so their FK checks remain compatible with this ordering.
 * Existing owners remain explicit rather than hidden behind a service locator.
 */
@Suppress("LongParameterList")
class RunRetryService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val commands: RunRetryCommandRepository,
    private val records: ResolutionRunRetryRecords,
    private val reviews: RunRetryReviewService,
    private val retries: ResolutionRunRetryService,
    private val identities: RunSupervisorAssignmentIdentityGenerator,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Atomically supersedes a failed run, creates its sole fresh successor, and attributes successor supervision.
     *
     * Exact replay belongs only to the originating actor with current resolver evidence, even after handover.
     * It acknowledges history without changing supervision. The actor must come from a verified session.
     * Runtime planning conflicts propagate as typed errors mapped to the same BFF conflict.
     *
     * @throws RunRetryDisabledException while rollout is disabled.
     * @throws AssignedRunNotFoundException for missing/foreign context, expired authority or another actor's receipt.
     * @throws RunRetryConflictException for changed intent, stale snapshots,
     * exhausted policy or unrelated existing retry.
     */
    fun retry(command: RetrySupervisedRunCommand): RunRetryCommandRecording =
        transactions.required {
            if (!enabled) throw RunRetryDisabledException()
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            commands.lockCommand(tenant, command.commandId)
            requireAuthority(tenant, actor)
            commands.find(tenant, command.commandId)?.let { stored ->
                return@required replay(command, stored)
            }
            val state = checkNotNull(records.transitions.lockState(tenant, run)) { "run state is absent" }
            val current =
                assignments.findAssigned(tenant, run, actor, clock.instant())
                    ?: throw AssignedRunNotFoundException()
            if (current.revision != command.expectedSupervisionRevision) throw RunRetryConflictException()
            if (state.version != command.expectedRunVersion || records.retries.find(tenant, run) != null) {
                throw RunRetryConflictException()
            }
            val start = checkNotNull(records.runs.find(tenant, run)) { "run start is absent" }.run
            val plan = requireReviewedPlan(command, start, state)
            val result = retries.retry(tenant.value, run.value, plan.caseStreamVersion, actor.value, plan)
            // Never adopt an internal retry or an earlier unrelated command, even if it names the same successor.
            if (!result.recording.created) throw RunRetryConflictException()
            record(command, current, result)
        }

    private fun replay(
        command: RetrySupervisedRunCommand,
        stored: StoredRunRetryCommand,
    ): RunRetryCommandRecording {
        if (stored.command.actorId != command.actorId || stored.command.runId != command.runId) {
            throw AssignedRunNotFoundException()
        }
        if (stored.command != command) throw RunRetryConflictException()
        return RunRetryCommandRecording(stored, created = false)
    }

    private fun requireReviewedPlan(
        command: RetrySupervisedRunCommand,
        start: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
    ): ResolutionRunPlan {
        val evaluated = reviews.evaluate(TenantId(command.tenantId), start, state)
        val plan = evaluated.plan ?: throw RunRetryConflictException()
        val samePlan = evaluated.reason == null && ReviewedRunRetryPlan.from(plan) == command.plan
        val samePolicy =
            evaluated.eligibility.revision.value == command.retryPolicyRevision &&
                evaluated.eligibility.maximumAttempts == command.maximumAttempts
        if (!samePlan || !samePolicy) throw RunRetryConflictException()
        return plan
    }

    private fun record(
        command: RetrySupervisedRunCommand,
        current: CurrentRunSupervision,
        result: ResolutionRunRetryExecution,
    ): RunRetryCommandRecording {
        val tenant = TenantId(command.tenantId)
        val actor = HumanActorId(command.actorId)
        val event = result.recording.storedEvent.event
        val authority = requireAuthority(tenant, actor)
        if (authority.evidence.id != checkNotNull(event.authorization).authorityEvidenceId) {
            throw RunRetryConflictException()
        }
        val assignment =
            assignments.create(
                tenant,
                RunSupervisorAssignment(
                    identities.next(),
                    result.replacementRun.run.id,
                    actor,
                    authority.evidence.id,
                    command.commandId,
                    null,
                    clock.instant(),
                    retryCommandId = command.commandId,
                ),
            )
        val receipt =
            commands.create(
                tenant,
                StoredRunRetryCommand(
                    command,
                    result.replacementRun.run.id.value,
                    assignment.assignment.id,
                    authority.evidence.id.value,
                    event.id.value,
                    result.replacementRun.run.attemptNumber - 1,
                    current.supervisedAt,
                    event.occurredAt,
                    result.recording.storedEvent.recordedAt,
                ),
            )
        return RunRetryCommandRecording(receipt, created = true)
    }

    private fun requireAuthority(
        tenant: TenantId,
        actor: HumanActorId,
    ) = records.authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
        ?: throw AssignedRunNotFoundException()
}
