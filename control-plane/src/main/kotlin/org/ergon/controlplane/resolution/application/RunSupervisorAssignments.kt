package org.ergon.controlplane.resolution.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal const val MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH = 200
internal const val MAX_ASSIGNED_RUN_PAGE_SIZE = 100

/**
 * Immutable first supervisor assignment for one run.
 *
 * The assignee was a resolver when this fact was recorded. That historical
 * evidence does not authorize a later read: the reader must also have current
 * resolver evidence and remain the exact assignee.
 */
data class RunSupervisorAssignment(
    val id: UUID,
    val runId: ResolutionRunId,
    val supervisorActorId: HumanActorId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
    val commandId: UUID,
    val assigningMachineSubject: String,
    val assignedAt: Instant,
) {
    init {
        require(
            assigningMachineSubject.isNotBlank() &&
                assigningMachineSubject.length <= MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH,
        ) {
            "assigning machine subject must contain 1..$MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH characters"
        }
    }
}

/** Immutable assignment paired with its database recording instant. */
data class StoredRunSupervisorAssignment(
    val assignment: RunSupervisorAssignment,
    val recordedAt: Instant,
)

/** First insert or exact machine-command replay with its original durable assignment. */
data class RunSupervisorAssignmentRecording(
    val stored: StoredRunSupervisorAssignment,
    val created: Boolean,
)

/** Active assigned run coordinates used for bounded browser discovery. */
data class AssignedRunOverview(
    val assignmentId: UUID,
    val runId: ResolutionRunId,
    val caseId: CaseId,
    val state: ResolutionRunState,
    val stateVersion: Long,
    val stateUpdatedAt: Instant,
    val assignedAt: Instant,
)

/** An indivisible keyset cursor over immutable assignment order. */
data class AssignedRunCursor(
    val assignedAt: Instant,
    val assignmentId: UUID,
)

/** A bounded page that never claims a stable total under concurrent writes. */
data class AssignedRunPage(
    val entries: List<AssignedRunOverview>,
    val nextCursor: AssignedRunCursor?,
)

/** Assigned run start and current state observed in one application read. */
data class AssignedRunDetail(
    val assignment: StoredRunSupervisorAssignment,
    val start: StoredResolutionRunStart,
    val state: ResolutionRunStateSnapshot,
)

/**
 * Tenant-scoped persistence for initial run supervision and assigned discovery.
 *
 * [lockRun] must execute inside the caller's transaction before checking or
 * inserting an assignment. It serializes competing first assignments without
 * making the assignee's browser read a lock-taking operation.
 */
interface RunSupervisorAssignmentRepository {
    /** @return whether the tenant-scoped run exists and is locked for this transaction. */
    fun lockRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): Boolean

    /** @return the assignment with [commandId], if recorded in [tenantId]. */
    fun findByCommandId(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunSupervisorAssignment?

    /** @return the first assignment of [runId], if present in [tenantId]. */
    fun findByRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredRunSupervisorAssignment?

    /**
     * Returns an assignment only when actor, run, tenant, and current resolver
     * evidence agree at [at]. This is the first private browser-detail lookup.
     */
    fun findAssigned(
        tenantId: TenantId,
        runId: ResolutionRunId,
        actorId: HumanActorId,
        at: Instant,
    ): StoredRunSupervisorAssignment?

    /**
     * Lists at most [limit] active runs in immutable assignment order.
     *
     * The SQL predicate must retain tenant, assignee, and current resolver
     * evidence at [at]. [after] is an indivisible keyset coordinate.
     */
    fun listAssigned(
        tenantId: TenantId,
        actorId: HumanActorId,
        at: Instant,
        limit: Int,
        after: AssignedRunCursor?,
    ): List<AssignedRunOverview>

    /** Inserts a first assignment after [lockRun], failing on conflicting identities. */
    fun create(
        tenantId: TenantId,
        assignment: RunSupervisorAssignment,
    ): StoredRunSupervisorAssignment
}

/** Supplies unpredictable assignment identities without coupling the use case to UUID generation. */
fun interface RunSupervisorAssignmentIdentityGenerator {
    fun next(): UUID
}

/** Exact, idempotent instruction from a machine principal trusted for assignment. */
data class AssignRunSupervisorCommand(
    val tenantId: UUID,
    val runId: UUID,
    val supervisorActorId: UUID,
    val commandId: UUID,
    val assigningMachineSubject: String,
)

/** A run does not exist in the requested tenant. */
class SupervisedRunNotFoundException : RuntimeException("resolution run was not found")

/** The selected actor has no current resolver evidence in the requested tenant. */
class RunSupervisorNotEligibleException : RuntimeException("run supervisor has no current resolver authority")

/** An assignment or command identity is already bound to different intent. */
class RunSupervisorAssignmentConflictException :
    RuntimeException(
        "run supervisor assignment conflicts with recorded intent",
    )

/** The browser actor cannot see the requested assigned run. */
class AssignedRunNotFoundException : RuntimeException("assigned resolution run was not found")

/**
 * Creates one durable, attributable first assignment from a machine command.
 *
 * Browser actors cannot invoke this use case through the BFF. The inbound
 * adapter must authenticate the machine issuer, tenant, audience, and dedicated scope
 * before passing its verified subject. Exact command replays return the same
 * assignment; a different actor or run never takes over an existing grant.
 */
class RunSupervisorAssignmentService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val identities: RunSupervisorAssignmentIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Assigns a currently authorized resolver to one existing run.
     *
     * @throws SupervisedRunNotFoundException when the tenant-scoped run is absent.
     * @throws RunSupervisorNotEligibleException when the actor lacks current resolver evidence.
     * @throws RunSupervisorAssignmentConflictException when the run or command was used for different intent.
     */
    fun assign(command: AssignRunSupervisorCommand): RunSupervisorAssignmentRecording =
        transactionRunner.required {
            val tenantId = TenantId(command.tenantId)
            val runId = ResolutionRunId(command.runId)
            val actorId = HumanActorId(command.supervisorActorId)
            require(
                command.assigningMachineSubject.isNotBlank() &&
                    command.assigningMachineSubject.length <= MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH,
            ) {
                "assigning machine subject must contain 1..$MAX_ASSIGNING_MACHINE_SUBJECT_LENGTH characters"
            }
            if (!assignments.lockRun(tenantId, runId)) throw SupervisedRunNotFoundException()

            assignments.findByCommandId(tenantId, command.commandId)?.let { recorded ->
                if (!recorded.matches(command)) throw RunSupervisorAssignmentConflictException()
                return@required RunSupervisorAssignmentRecording(recorded, created = false)
            }
            if (assignments.findByRun(tenantId, runId) != null) throw RunSupervisorAssignmentConflictException()

            val now = clock.instant()
            val evidence =
                authorities.findCurrent(tenantId, actorId, ApprovalAuthority.RESOLVER, null, now)
                    ?: throw RunSupervisorNotEligibleException()
            RunSupervisorAssignmentRecording(
                stored =
                    assignments.create(
                        tenantId,
                        RunSupervisorAssignment(
                            id = identities.next(),
                            runId = runId,
                            supervisorActorId = actorId,
                            authorityEvidenceId = evidence.evidence.id,
                            commandId = command.commandId,
                            assigningMachineSubject = command.assigningMachineSubject,
                            assignedAt = now,
                        ),
                    ),
                created = true,
            )
        }
}

/**
 * Read-only browser use case for a resolver's explicitly assigned runs.
 *
 * Current resolver evidence and the assignment must both hold at the same
 * application-clock instant. Missing and unauthorized detail reads collapse
 * to one not-found outcome before private run snapshots are loaded.
 */
class AssignedRunConsoleService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /** Lists at most 100 active assigned runs, with no stable total count. */
    fun list(
        tenantId: UUID,
        actorId: UUID,
        limit: Int,
        after: AssignedRunCursor?,
    ): AssignedRunPage {
        require(limit in 1..MAX_ASSIGNED_RUN_PAGE_SIZE) {
            "limit must be in 1..$MAX_ASSIGNED_RUN_PAGE_SIZE"
        }
        return transactionRunner.required {
            val tenant = TenantId(tenantId)
            val actor = HumanActorId(actorId)
            val at = clock.instant()
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at) == null) {
                return@required AssignedRunPage(emptyList(), null)
            }
            val rows = assignments.listAssigned(tenant, actor, at, limit + 1, after)
            val entries = rows.take(limit)
            val next =
                if (rows.size > limit) {
                    entries.last().let { AssignedRunCursor(it.assignedAt, it.assignmentId) }
                } else {
                    null
                }
            AssignedRunPage(entries, next)
        }
    }

    /**
     * Returns the assigned run even after it reaches a terminal state so a
     * polling console can report completion without inventing a live trace.
     *
     * @throws AssignedRunNotFoundException for absent, other-tenant, other-actor,
     *   unassigned, or no-longer-authorized runs.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): AssignedRunDetail =
        transactionRunner.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val actor = HumanActorId(actorId)
            val at = clock.instant()
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at) == null) {
                throw AssignedRunNotFoundException()
            }
            val assignment =
                assignments.findAssigned(tenant, run, actor, at)
                    ?: throw AssignedRunNotFoundException()
            val start = runs.find(tenant, run) ?: error("assigned run start is absent")
            val state = transitions.findState(tenant, run) ?: error("assigned run state is absent")
            check(state.runId == start.run.id) { "assigned run state belongs to another run" }
            AssignedRunDetail(assignment, start, state)
        }
}

private fun StoredRunSupervisorAssignment.matches(command: AssignRunSupervisorCommand): Boolean =
    assignment.runId.value == command.runId &&
        assignment.supervisorActorId.value == command.supervisorActorId &&
        assignment.assigningMachineSubject == command.assigningMachineSubject
