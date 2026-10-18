package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunCancelled
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Exact reviewed intent; the session supplies actor/tenant, while reason and occurrence
 * are server-owned. [expectedRunVersion] must be zero; the independent supervision
 * epoch is validated by [RunSupervisionRevision] and checked again when confirming.
 * @throws IllegalArgumentException for a nonzero expected run version.
 */
data class CancelSupervisedRunCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val expectedSupervisionRevision: RunSupervisionRevision,
    val expectedRunVersion: Long,
) {
    init {
        require(expectedRunVersion == 0L) { "cancellation requires a version-zero run" }
    }
}

/** Private immutable audit; neither attribution nor this receipt grants current Console access. */
data class StoredRunCancellation(
    val command: CancelSupervisedRunCommand,
    val supervisedAt: Instant,
    val authorityEvidenceId: UUID,
    val event: ResolutionRunCancelled,
    val recordedAt: Instant,
)

data class RunCancellationRecording(
    val stored: StoredRunCancellation,
    val created: Boolean,
)

/** Caller holds run, command and state locks in that order in one transaction. */
interface RunCancellationRepository {
    /** Serializes UUID reuse across runs in this tenant without disclosing existing intent. */
    fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    )

    /** History only; caller checks originating actor, current resolver authority and exact intent. */
    fun find(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunCancellation?

    /** Must be queried after the shared run fence; any authorization/execution history denies cancellation. */
    fun hasExecutionHistory(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): Boolean

    /** Atomically appends event, projects terminal state and records exact attributed command. */
    fun append(
        tenantId: TenantId,
        cancellation: StoredRunCancellation,
    ): StoredRunCancellation
}

/** Stable advisory denials; only ELIGIBLE permits confirmation, subject to fresh command gates. */
enum class RunCancellationReviewReason {
    DISABLED,
    RUN_NOT_UNTOUCHED,
    AUTHORIZATION_OR_EXECUTION_RECORDED,
    ELIGIBLE,
}

/** Advisory coordinates, not an authority token; no approval, provider or evidence identities are disclosed. */
data class RunCancellationReview(
    val supported: Boolean,
    val observedAt: Instant,
    val supervisionRevision: Long,
    val runVersion: Long,
    val canCancel: Boolean,
    val reason: RunCancellationReviewReason,
)

/** Cohesive authority/durability sources for pre-authorization termination. */
data class RunCancellationRecords(
    val assignments: RunSupervisorAssignmentRepository,
    val authorities: HumanAuthorityRepository,
    val transitions: ResolutionRunTransitionRepository,
    val cancellations: RunCancellationRepository,
)

/**
 * Terminates only untouched runs, without remote I/O or effects on case, budgets
 * or follow-up. Run NO KEY UPDATE precedes command and state locks, matching all
 * authorization writers' fence. Authority is evaluated after waits.
 */
class RunCancellationService(
    private val records: RunCancellationRecords,
    private val identities: ResolutionRunEventIdentityGenerator,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Fresh supervisor/resolver review, including disabled and terminal context.
     * @throws AssignedRunNotFoundException for missing, foreign or unauthorized context.
     */
    fun review(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunCancellationReview =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val current =
                records.assignments.findAssigned(tenant, run, HumanActorId(actorId), clock.instant())
                    ?: throw AssignedRunNotFoundException()
            val state = checkNotNull(records.transitions.findState(tenant, run)) { "assigned run state is absent" }
            val reason =
                when {
                    !enabled -> RunCancellationReviewReason.DISABLED

                    !untouched(state.state, state.version) -> RunCancellationReviewReason.RUN_NOT_UNTOUCHED

                    records.cancellations.hasExecutionHistory(
                        tenant,
                        run,
                    ) -> RunCancellationReviewReason.AUTHORIZATION_OR_EXECUTION_RECORDED

                    else -> RunCancellationReviewReason.ELIGIBLE
                }
            RunCancellationReview(
                enabled,
                clock.instant(),
                current.revision.value,
                state.version,
                reason == RunCancellationReviewReason.ELIGIBLE,
                reason,
            )
        }

    /**
     * First application requires the current reviewed supervisor epoch. Exact replay
     * requires the original actor and current resolver evidence, not supervision.
     * An unchanged explicit uncertain replay may first apply an absent command.
     * @throws RunCancellationDisabledException while commands are off, including replay.
     * @throws AssignedRunNotFoundException for absent/foreign context or missing current resolver authority.
     * @throws RunCancellationConflictException for changed intent, epoch, state or authorization history.
     */
    fun cancel(command: CancelSupervisedRunCommand): RunCancellationRecording =
        transactions.required {
            if (!enabled) throw RunCancellationDisabledException()
            val tenant = TenantId(command.tenantId)
            val run = ResolutionRunId(command.runId)
            val actor = HumanActorId(command.actorId)
            if (!records.assignments.lockRun(tenant, run)) throw AssignedRunNotFoundException()
            records.cancellations.lockCommand(tenant, command.commandId)
            requireAuthority(tenant, actor)
            records.cancellations.find(tenant, command.commandId)?.let { stored ->
                if (stored.command.actorId != command.actorId || stored.command.runId != command.runId) {
                    throw AssignedRunNotFoundException()
                }
                if (stored.command != command) throw RunCancellationConflictException()
                return@required RunCancellationRecording(stored, created = false)
            }
            val state = checkNotNull(records.transitions.lockState(tenant, run)) { "run state is absent" }
            val current =
                records.assignments.findAssigned(tenant, run, actor, clock.instant())
                    ?: throw AssignedRunNotFoundException()
            if (current.revision != command.expectedSupervisionRevision ||
                !untouched(state.state, state.version) || records.cancellations.hasExecutionHistory(tenant, run)
            ) {
                throw RunCancellationConflictException()
            }
            val authority = requireAuthority(tenant, actor)
            val event =
                ResolutionRunCancelled(
                    identities.next(),
                    run,
                    state.state,
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                )
            val stored =
                records.cancellations.append(
                    tenant,
                    StoredRunCancellation(
                        command,
                        current.supervisedAt,
                        authority.evidence.id.value,
                        event,
                        event.occurredAt,
                    ),
                )
            RunCancellationRecording(stored, created = true)
        }

    private fun requireAuthority(
        tenant: TenantId,
        actor: HumanActorId,
    ) = records.authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, clock.instant())
        ?: throw AssignedRunNotFoundException()
}

private fun untouched(
    state: ResolutionRunState,
    version: Long,
): Boolean =
    version == 0L &&
        (state == ResolutionRunState.WAITING_FOR_APPROVAL || state == ResolutionRunState.READY_FOR_AUTHORIZATION)

class RunCancellationDisabledException : RuntimeException("browser run cancellation is disabled")

/** Reviewed intent cannot first apply; no terminal transition commits. */
class RunCancellationConflictException : RuntimeException("run cancellation conflicts with reviewed intent")

/** A supported writer attempted new authorization history after terminal cancellation. */
class ResolutionRunCancelledException : RuntimeException("cancelled run cannot acquire new authorization")
