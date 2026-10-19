package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Existing prerequisite owners reused atomically, not an alternative grant or consumption implementation. */
data class RunExecutionPrerequisites(
    val reviews: RunApprovalReviewReader,
    val requests: ApprovalRequestRepository,
    val grants: CapabilityAuthorizationGrantService,
    val consumptions: CapabilityAuthorizationConsumptionService,
)

/**
 * Commits attributed dispatch admission before any connector I/O. Lock order is
 * run, command key, attempt key, state, decision, grant. The run lock
 * serializes handover and request replacement; external legacy writers retain
 * their existing grant/consumption locks and uniqueness constraints.
 */
@Suppress("LongParameterList") // Authority, state, prerequisites and audit remain explicit.
class RunExecutionAdmissionService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val runs: ResolutionRunRepository,
    private val states: ResolutionRunTransitionRepository,
    private val prerequisites: RunExecutionPrerequisites,
    private val commands: RunExecutionCommandRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
    private val controls: RunCommandControlGate,
) {
    /**
     * Admits the exact reviewed approved step, or acknowledges original intent without dispatch.
     * Foreign command identities are neutral absence. Existing internal grants are
     * conflicts, never silently attributed to a newly supplied browser command.
     * [control] is checked after reservation lock waits; failure rolls back grant, consumption and dispatch
     * admission together.
     * @throws RunControlRequiredException when enforced first admission lacks control.
     * @throws RunControlConflictException for expired/mismatched control or changed replay fence.
     * @throws AssignedRunNotFoundException for absent/private runs or foreign intent.
     * @throws RunExecutionConflictException for stale or unavailable prerequisites.
     * @throws RunExecutionDisabledException while the separate rollout flag is off.
     */
    fun admit(
        command: ExecuteRunCommand,
        control: RunCommandControl? = null,
    ): RunExecutionAdmission =
        transactions.required {
            val key =
                RunControlCommandKey(
                    command.tenantId,
                    command.runId,
                    RunControlCommandKind.EXECUTION,
                    command.commandId,
                )
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            gate(tenant, run, HumanActorId(command.actorId))
            commands.lockCommand(tenant, command.commandId)
            commands.findCommand(tenant, command.commandId)?.let {
                requireOwner(it, command.actorId, command.runId)
                requireResolver(tenant, HumanActorId(command.actorId))
                if (it.command != command) throw RunExecutionConflictException()
                controls.replay(key, control)
                return@required RunExecutionAdmission(it, dispatch = false)
            }
            val attempt = command.firstAttempt()
            commands.lockAttempt(tenant, attempt.attemptId)
            if (commands.findAttempt(tenant, attempt.attemptId) != null || commands.findByRun(tenant, run) != null) {
                throw RunExecutionConflictException()
            }
            requireState(tenant, run, command.expectedRunVersion)
            val current = current(attempt)
            if (commands.hasPriorAuthorization(tenant, run)) throw RunExecutionConflictException()
            val start = checkNotNull(runs.find(tenant, run)).run
            val request =
                prerequisites.requests.find(tenant, ApprovalRequestId(command.requestId))?.request
                    ?: throw RunExecutionConflictException()
            val latest = prerequisites.reviews.findLatest(tenant, run)
            val decision = latest?.decision
            val sameStep = request.stepId == start.stepId && request.authority.name == start.requiredApproval.name
            if (latest?.request?.request?.id != request.id || request.runId != run || !sameStep) {
                throw RunExecutionConflictException()
            }
            if (decision?.decisionId != command.decisionId || decision.outcome != ApprovalDecisionOutcome.APPROVED) {
                throw RunExecutionConflictException()
            }
            requireApprovalWindow(request, clock.instant())
            val consumption = reserve(command)
            // Grant/consumption lock waits cannot preserve an earlier authority check.
            val checked = current(attempt)
            check(checked.revision == current.revision) { "supervision changed while its run lock was held" }
            val admittedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
            requireApprovalWindow(request, admittedAt)
            val evidence = requireResolver(tenant, HumanActorId(command.actorId), admittedAt)
            val stored = commands.recordCommand(command, consumption, checked)
            commands.recordAttempt(attempt, checked, evidence, admittedAt)
            controls.admit(key, command.actorId, command.expectedSupervisionRevision, control)
            RunExecutionAdmission(stored, dispatch = true)
        }

    /**
     * Explicitly resumes the same consumption. Only its original actor, currently
     * supervising at the reviewed epoch, may admit a new attempt. Exact attempt
     * replay never dispatches, even after handover; handback needs the new epoch.
     * Already projected results cannot admit another attempt.
     * A new attempt needs current [control]; replay retains its original fence but neither renews nor dispatches.
     * @throws RunControlRequiredException when enforced new recovery lacks control.
     * @throws RunControlConflictException for expired/mismatched control or changed historical fence.
     */
    fun resume(
        command: ResumeRunExecutionCommand,
        control: RunCommandControl? = null,
    ): RunExecutionAdmission =
        transactions.required {
            // The first attempt shares the command UUID and its single admission.
            // Acknowledging it through the recovery route must not invent another admission.
            val kind =
                if (command.attemptId == command.commandId) {
                    RunControlCommandKind.EXECUTION
                } else {
                    RunControlCommandKind.EXECUTION_RECOVERY
                }
            val key =
                RunControlCommandKey(
                    command.tenantId,
                    command.runId,
                    kind,
                    command.attemptId,
                )
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            gate(tenant, run, HumanActorId(command.actorId))
            commands.lockCommand(tenant, command.commandId)
            val stored = commands.findCommand(tenant, command.commandId) ?: throw AssignedRunNotFoundException()
            requireOwner(stored, command.actorId, command.runId)
            commands.lockAttempt(tenant, command.attemptId)
            requireResolver(tenant, HumanActorId(command.actorId))
            commands.findAttempt(tenant, command.attemptId)?.let {
                if (it.command.commandId != command.commandId || it.command.actorId != command.actorId) {
                    throw AssignedRunNotFoundException()
                }
                if (it.command != command) throw RunExecutionConflictException()
                controls.replay(key, control)
                return@required RunExecutionAdmission(stored, dispatch = false)
            }
            requireState(tenant, run, command.expectedRunVersion)
            val current = current(command)
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val evidence = requireResolver(tenant, HumanActorId(command.actorId), at)
            commands.recordAttempt(command, current, evidence, at)
            controls.admit(key, command.actorId, command.expectedSupervisionRevision, control)
            RunExecutionAdmission(stored, dispatch = true)
        }

    private fun reserve(command: ExecuteRunCommand): StoredCapabilityAuthorizationConsumption =
        try {
            val grant = deriveGrant(command)
            prerequisites.consumptions.consume(command.tenantId, grant.grant.id.value)
        } catch (_: CapabilityAuthorizationGrantExpiredException) {
            throw RunExecutionConflictException()
        } catch (_: TenantCapabilityUnavailableException) {
            throw RunExecutionConflictException()
        }

    private fun deriveGrant(command: ExecuteRunCommand): StoredCapabilityAuthorizationGrant =
        try {
            prerequisites.grants.authorize(command.tenantId, command.decisionId)
        } catch (_: CapabilityAuthorizationGrantAlreadyExistsException) {
            throw RunExecutionConflictException()
        } catch (_: CapabilityAuthorizationWindowExpiredException) {
            throw RunExecutionConflictException()
        }

    private fun gate(
        tenant: TenantId,
        run: ResolutionRunId,
        actor: HumanActorId,
    ) {
        if (!enabled) throw RunExecutionDisabledException()
        if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
        requireResolver(tenant, actor)
    }

    private fun requireResolver(
        tenant: TenantId,
        actor: HumanActorId,
        at: Instant = clock.instant().truncatedTo(ChronoUnit.MICROS),
    ) = authorities
        .findCurrent(
            tenant,
            actor,
            ApprovalAuthority.RESOLVER,
            null,
            at,
        )?.evidence
        ?.id ?: throw AssignedRunNotFoundException()

    private fun current(command: ResumeRunExecutionCommand): CurrentRunSupervision {
        val current =
            assignments.findAssigned(
                TenantId(command.tenantId),
                ResolutionRunId(command.runId),
                HumanActorId(command.actorId),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
            ) ?: throw AssignedRunNotFoundException()
        if (current.revision != command.expectedSupervisionRevision) throw RunExecutionConflictException()
        return current
    }

    private fun requireState(
        tenant: TenantId,
        run: ResolutionRunId,
        expected: Long,
    ) {
        val state = checkNotNull(states.lockState(tenant, run)) { "assigned run state is absent" }
        val initial =
            state.state in
                setOf(
                    ResolutionRunState.WAITING_FOR_APPROVAL,
                    ResolutionRunState.READY_FOR_AUTHORIZATION,
                )
        if (state.version != expected || !initial) throw RunExecutionConflictException()
    }

    private fun requireApprovalWindow(
        request: ApprovalRequest,
        at: Instant,
    ) {
        if (at.isBefore(request.requestedAt) || !at.isBefore(request.expiresAt)) throw RunExecutionConflictException()
    }
}

internal fun requireOwner(
    stored: StoredRunExecutionCommand,
    actorId: java.util.UUID,
    runId: java.util.UUID,
) {
    if (stored.command.actorId != actorId || stored.command.runId != runId) throw AssignedRunNotFoundException()
}

private fun ExecuteRunCommand.firstAttempt() =
    ResumeRunExecutionCommand(
        tenantId,
        runId,
        actorId,
        commandId,
        commandId,
        expectedSupervisionRevision,
        expectedRunVersion,
    )
