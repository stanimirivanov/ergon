package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal const val MAX_INVOCATION_ATTEMPTS = 100

/** Null completion means no completion recorded, not live work, no dispatch or no provider effect. */
data class RecordedInvocationAttempt(
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val connector: ConnectorName,
    val preparedAt: Instant,
    val recordedAt: Instant,
    val completion: RecordedInvocationCompletion?,
)

data class RecordedInvocationCompletion(
    val call: CapabilityInvocationCompletion,
    val recordedAt: Instant,
)

/** Empty/truncated histories never assert complete coverage of historical or mixed-version writers. */
data class RecordedInvocationAttempts(
    val entries: List<RecordedInvocationAttempt>,
    val truncated: Boolean,
)

/**
 * Caller authorizes first and owns the transaction. One statement returns the
 * first 100 preparations ordered by recorded time/private identity, probing one
 * extra row for truncation. This is not causal order or a stable cross-read prefix.
 */
fun interface RunInvocationHistoryReader {
    fun read(
        tenant: TenantId,
        run: ResolutionRunId,
    ): RecordedInvocationAttempts
}

data class RunInvocationHistory(
    val observedAt: Instant,
    val supervisionRevision: RunSupervisionRevision,
    val attempts: RecordedInvocationAttempts,
)

/** Sanitized cross-epoch/internal/terminal history, without acquiring control or granting recovery rights. */
class RunInvocationHistoryService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val history: RunInvocationHistoryReader,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Gate the exact run at one authority observation instant before any history
     * lookup. Access can change afterwards; this is not continuous authority.
     * @throws AssignedRunNotFoundException for absent/foreign/unassigned/former
     *   supervisor or expired resolver evidence. Storage/invariant failures propagate.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunInvocationHistory =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val actor = HumanActorId(actorId)
            val at = clock.instant()
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at) == null) {
                throw AssignedRunNotFoundException()
            }
            val supervision = assignments.findAssigned(tenant, run, actor, at) ?: throw AssignedRunNotFoundException()
            RunInvocationHistory(at, supervision.revision, history.read(tenant, run))
        }
}
