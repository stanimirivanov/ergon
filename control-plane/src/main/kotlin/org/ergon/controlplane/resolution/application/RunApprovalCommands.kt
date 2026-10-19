package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalDecision
import org.ergon.resolution.domain.ApprovalDecisionBasis
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ApprovalRequestStatus
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exact immutable browser intent. The authenticated adapter supplies [actorId], never the request body. */
data class DecideRunApprovalCommand(
    val tenantId: UUID,
    val runId: UUID,
    val requestId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val expectedRevision: RunSupervisionRevision,
    val outcome: ApprovalDecisionOutcome,
)

/** Original command paired with persisted decision instants; a historical receipt is not current supervision. */
data class StoredRunApprovalCommand(
    val command: DecideRunApprovalCommand,
    val decision: RunApprovalDecisionSummary,
)

data class RunApprovalRecording(
    val stored: StoredRunApprovalCommand,
    val created: Boolean,
)

/**
 * Durable browser replay boundary. Call inside the application transaction, after
 * locking the run. [lockCommand] serializes a tenant/command across distinct runs.
 * [record] binds the decision to the exact supervision event and returns persisted
 * instants so first response and replay are identical despite PostgreSQL precision.
 */
interface RunApprovalCommandRepository {
    fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    )

    fun findByCommandId(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunApprovalCommand?

    fun record(
        command: DecideRunApprovalCommand,
        supervision: CurrentRunSupervision,
        decisionId: ApprovalDecisionId,
    ): StoredRunApprovalCommand
}

class RunApprovalDisabledException : RuntimeException("browser approval decisions are disabled")

class RunApprovalConflictException : RuntimeException("approval intent is stale or already answered")

class RunApprovalAuthorityMissingException : RuntimeException("matching approval authority is unavailable")

/**
 * Records a supervisor's human prerequisite only: no grant, invocation or state advancement.
 * Lock order is run, tenant/command, runtime state, request. This serializes handover
 * and request replacement while preserving the legacy request-only decision boundary.
 * No remote I/O enters the transaction. Both outcomes require exact scoped authority.
 */
@Suppress("LongParameterList") // Distinct authority, supervision, runtime and audit ports must remain explicit.
class RunApprovalDecisionService(
    private val records: ApprovalDecisionRecords,
    private val assignments: RunSupervisorAssignmentRepository,
    private val states: ResolutionRunTransitionRepository,
    private val commands: RunApprovalCommandRepository,
    private val reviewReader: RunApprovalReviewReader,
    private val identities: ApprovalDecisionIdentityGenerator,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
    private val controls: RunCommandControlGate,
) {
    /**
     * Applies a first command or acknowledges its originating actor's exact historical receipt.
     * Replay requires current resolver evidence but neither current supervision nor
     * still-current approval evidence; it performs no new decision after a handback.
     * [control] is trusted session context with the original public fence, not approval authority.
     * @throws RunControlRequiredException when enforced first admission lacks control.
     * @throws RunControlConflictException for expired/mismatched control or changed replay fence.
     * @throws AssignedRunNotFoundException for private absence, non-owner or foreign receipts.
     * @throws RunApprovalConflictException for stale epochs, changed intent, expiry, answered or non-waiting requests.
     * @throws RunApprovalAuthorityMissingException for absent exact request authority/scope.
     * @throws RunApprovalDisabledException while commands are disabled.
     */
    fun decide(
        command: DecideRunApprovalCommand,
        control: RunCommandControl? = null,
    ): RunApprovalRecording =
        transactions.required {
            if (!enabled) throw RunApprovalDisabledException()
            val key =
                RunControlCommandKey(
                    command.tenantId,
                    command.runId,
                    RunControlCommandKind.APPROVAL,
                    command.commandId,
                )
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            // Persisted request/evidence boundaries have microsecond precision.
            // Flooring before validation cannot round a valid decision onto expiry.
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            requireCurrentResolver(tenant, actor, now)
            commands.lockCommand(tenant, command.commandId)
            commands.findByCommandId(tenant, command.commandId)?.let { stored ->
                requireCurrentResolver(tenant, actor, clock.instant().truncatedTo(ChronoUnit.MICROS))
                return@required replay(command, stored).also { controls.replay(key, control) }
            }
            recordFirstDecision(command, tenant, run, actor, now).also {
                controls.admit(key, command.actorId, command.expectedRevision, control)
            }
        }

    private fun replay(
        command: DecideRunApprovalCommand,
        stored: StoredRunApprovalCommand,
    ): RunApprovalRecording {
        if (stored.command.actorId != command.actorId || stored.command.runId != command.runId) {
            throw AssignedRunNotFoundException()
        }
        if (stored.command != command) throw RunApprovalConflictException()
        return RunApprovalRecording(stored, created = false)
    }

    private fun recordFirstDecision(
        command: DecideRunApprovalCommand,
        tenant: TenantId,
        run: ResolutionRunId,
        actor: HumanActorId,
        accessAt: Instant,
    ): RunApprovalRecording {
        val current = requireWaitingSupervision(tenant, run, actor, command.expectedRevision, accessAt)
        val request = lockRunRequest(tenant, run, ApprovalRequestId(command.requestId))
        // State/request lock waits cannot preserve an earlier validity check.
        // Both resolver and exact approval authority use this final decision instant.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        requireCurrentResolver(tenant, actor, now)
        requirePendingRequest(tenant, run, request, now)
        val start = checkNotNull(records.runs.find(tenant, run)).run
        check(request.stepId == start.stepId && request.authority.name == start.requiredApproval.name) {
            "approval request does not match the pinned run"
        }
        val scope = start.caseId.takeIf { request.authority == ApprovalAuthority.REQUESTER }
        val evidence =
            records.authorities.findCurrent(tenant, actor, request.authority, scope, now)
                ?: throw RunApprovalAuthorityMissingException()
        val decision =
            ApprovalDecision.record(
                identities.next(),
                ApprovalDecisionBasis(request, start.caseId, evidence.evidence),
                command.outcome,
                now,
            )
        records.decisions.create(tenant, decision)
        return RunApprovalRecording(commands.record(command, current, decision.id), created = true)
    }

    private fun requirePendingRequest(
        tenant: TenantId,
        run: ResolutionRunId,
        request: ApprovalRequest,
        now: Instant,
    ) {
        val isCurrent = !now.isBefore(request.requestedAt) && request.statusAt(now) == ApprovalRequestStatus.PENDING
        val latest =
            reviewReader
                .findLatest(tenant, run)
                ?.request
                ?.request
                ?.id
        if (!isCurrent || latest != request.id || records.decisions.findIdByRequest(tenant, request.id) != null) {
            throw RunApprovalConflictException()
        }
    }

    private fun lockRunRequest(
        tenant: TenantId,
        run: ResolutionRunId,
        requestId: ApprovalRequestId,
    ): ApprovalRequest =
        records.requests
            .lockForDecision(tenant, requestId)
            ?.request
            ?.takeIf { it.runId == run }
            ?: throw AssignedRunNotFoundException()

    private fun requireCurrentResolver(
        tenant: TenantId,
        actor: HumanActorId,
        at: Instant,
    ) {
        if (records.authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at) == null) {
            throw AssignedRunNotFoundException()
        }
    }

    private fun requireWaitingSupervision(
        tenant: TenantId,
        run: ResolutionRunId,
        actor: HumanActorId,
        expected: RunSupervisionRevision,
        now: Instant,
    ): CurrentRunSupervision {
        val current = assignments.findAssigned(tenant, run, actor, now) ?: throw AssignedRunNotFoundException()
        val state = states.lockState(tenant, run) ?: error("assigned run state is absent")
        if (current.revision != expected || state.state != ResolutionRunState.WAITING_FOR_APPROVAL) {
            throw RunApprovalConflictException()
        }
        return current
    }
}
