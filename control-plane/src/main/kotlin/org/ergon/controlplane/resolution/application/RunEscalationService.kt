package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Clock

/**
 * Confirms terminal human follow-up, not a new attempt or invocation recovery.
 * Lock order matches retry/handover: run NO KEY UPDATE, tenant command, run state.
 * The existing runtime owner creates event/projection/follow-up in this same short
 * transaction. Internal writers serialize on state; no remote I/O enters.
 */
@Suppress("LongParameterList") // Explicit runtime, authority and transaction owners; no service locator.
class RunEscalationService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val commands: RunEscalationCommandRepository,
    private val records: ResolutionRunEscalationRecords,
    private val policy: ResolutionRetryPolicy,
    private val escalations: ResolutionRunEscalationService,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
    private val controls: RunCommandControlGate,
) {
    /**
     * First application requires current supervision/resolver authority and exact reviewed exhaustion.
     * Replay requires the original actor and current resolver authority, not continued supervision;
     * its historical work identity never confers current case or follow-up access.
     * [control] retains the original fence and trusted session binding; failed admission rolls back terminal work
     * creation.
     * @throws RunControlRequiredException when enforced first admission lacks control.
     * @throws RunControlConflictException for expired/mismatched control or changed replay fence.
     * @throws RunEscalationDisabledException while commands are disabled, including replay.
     * @throws AssignedRunNotFoundException for missing/foreign context or expired authority.
     * @throws RunEscalationConflictException for changed intent, epoch, state, policy or prior escalation.
     */
    fun escalate(
        command: EscalateSupervisedRunCommand,
        control: RunCommandControl? = null,
    ): RunEscalationCommandRecording =
        transactions.required {
            if (!enabled) throw RunEscalationDisabledException()
            val key =
                RunControlCommandKey(
                    command.tenantId,
                    command.runId,
                    RunControlCommandKind.ESCALATION,
                    command.commandId,
                )
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            commands.lockCommand(tenant, command.commandId)
            requireAuthority(tenant, actor)
            commands.find(tenant, command.commandId)?.let {
                return@required replay(command, it).also { controls.replay(key, control) }
            }
            val state = checkNotNull(records.transitions.lockState(tenant, run)) { "run state is absent" }
            val current =
                assignments.findAssigned(tenant, run, actor, clock.instant())
                    ?: throw AssignedRunNotFoundException()
            if (current.revision != command.expectedSupervisionRevision ||
                state.version != command.expectedRunVersion ||
                state.state != ResolutionRunState.ACTION_FAILED
            ) {
                throw RunEscalationConflictException()
            }
            val denial = requireReviewedExhaustion(command)
            val result = escalations.escalate(command.tenantId, command.runId, command.actorId, denial)
            // An internal or unrelated command's escalation cannot become this browser actor's receipt.
            if (!result.escalation.created) throw RunEscalationConflictException()
            val event = result.escalation.storedEvent.event
            val authority = requireAuthority(tenant, actor)
            if (authority.evidence.id != event.authorization.authorityEvidenceId) throw RunEscalationConflictException()
            val stored =
                commands.create(
                    tenant,
                    StoredRunEscalationCommand(
                        command,
                        current.supervisedAt,
                        authority.evidence.id.value,
                        event.id.value,
                        result.followUp.item.id.value,
                        event.occurredAt,
                        result.escalation.storedEvent.recordedAt,
                    ),
                )
            controls.admit(key, command.actorId, command.expectedSupervisionRevision, control)
            RunEscalationCommandRecording(stored, created = true)
        }

    private fun requireReviewedExhaustion(command: EscalateSupervisedRunCommand): ResolutionRetryEligibility.Denied {
        val tenant = TenantId(command.tenantId)
        val run = ResolutionRunId(command.runId)
        val start = checkNotNull(records.runs.find(tenant, run)) { "run start is absent" }.run
        val denial =
            policy.evaluate(start.attemptNumber) as? ResolutionRetryEligibility.Denied
                ?: throw RunEscalationConflictException()
        val samePolicy =
            denial.revision.value == command.retryPolicyRevision &&
                denial.maximumAttempts == command.maximumAttempts &&
                denial.sourceAttemptNumber == command.sourceAttemptNumber
        if (!samePolicy || records.escalations.find(tenant, run) != null) {
            throw RunEscalationConflictException()
        }
        return denial
    }

    private fun replay(
        command: EscalateSupervisedRunCommand,
        stored: StoredRunEscalationCommand,
    ): RunEscalationCommandRecording {
        if (stored.command.actorId != command.actorId || stored.command.runId != command.runId) {
            throw AssignedRunNotFoundException()
        }
        if (stored.command != command) throw RunEscalationConflictException()
        return RunEscalationCommandRecording(stored, created = false)
    }

    private fun requireAuthority(
        tenant: TenantId,
        actor: HumanActorId,
    ) = records.authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
        ?: throw AssignedRunNotFoundException()
}
