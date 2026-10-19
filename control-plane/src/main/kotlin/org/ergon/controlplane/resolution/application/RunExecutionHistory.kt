package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal const val MAX_EXECUTION_ADMISSIONS = 100

enum class RunExecutionAdmissionKind { INITIAL, RECOVERY }

/** A committed admission, not evidence of provider dispatch, completion, or a separately measured outcome. */
data class RecordedExecutionAdmission(
    val kind: RunExecutionAdmissionKind,
    val supervisionRevision: RunSupervisionRevision,
    val admittedAt: Instant,
    val recordedAt: Instant,
)

/** Null from the reader means no browser command; an existing command must have its initial admission. */
data class RecordedExecutionAdmissions(
    val entries: List<RecordedExecutionAdmission>,
    val truncated: Boolean,
)

/**
 * Sanitized, bounded audit lookup. Caller owns the transaction and must authorize
 * before calling. Return the first 100 rows ordered by database recording time,
 * then private attempt UUID; the order is deterministic, not causal. Read one
 * extra row to detect truncation in the same statement snapshot. No writes occur.
 */
fun interface RunExecutionHistoryReader {
    fun read(
        tenant: TenantId,
        run: ResolutionRunId,
    ): RecordedExecutionAdmissions?
}

/** Observation-time authorization and history are distinct from current permission to execute or recover. */
data class RunExecutionHistory(
    val observedAt: Instant,
    val supervisionRevision: RunSupervisionRevision,
    val admissions: RecordedExecutionAdmissions?,
)

/** Reads only the requested run's browser audit, without loading unrelated case evidence or acquiring control. */
class RunExecutionHistoryService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val history: RunExecutionHistoryReader,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Current supervisor and tenant-wide resolver evidence are checked at one
     * observation instant before audit lookup, including for terminal runs.
     * Historical epochs remain visible after handover, without original identities.
     * No lease or execution-command rollout flag is required for this read.
     *
     * @throws AssignedRunNotFoundException for absent, other-tenant, unassigned,
     *   former-supervisor or no-longer-authorized access, before reading audit.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunExecutionHistory =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val actor = HumanActorId(actorId)
            val at = clock.instant()
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at) == null) {
                throw AssignedRunNotFoundException()
            }
            val supervision = assignments.findAssigned(tenant, run, actor, at) ?: throw AssignedRunNotFoundException()
            RunExecutionHistory(at, supervision.revision, history.read(tenant, run))
        }
}
