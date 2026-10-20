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

/** Navigation coordinates only; neither the epoch nor the link authorizes a later detail read or command. */
data class VisibleRetrySuccessor(
    val runId: ResolutionRunId,
    val supervisionRevision: RunSupervisionRevision,
)

/**
 * Observation-time predecessor access and its independently visible direct successor.
 * A null successor deliberately conflates absent retry, absent assignment and denied
 * target access. It must not be presented as proof that no retry occurred.
 */
data class RunRetrySuccessor(
    val observedAt: Instant,
    val supervisionRevision: RunSupervisionRevision,
    val successor: VisibleRetrySuccessor?,
)

/** Recovers navigation from runtime retry history, not an originating browser command receipt. */
class RunRetrySuccessorService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val authorities: HumanAuthorityRepository,
    private val retries: ResolutionRunRetryRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Requires current exact supervision and tenant-wide resolver evidence before
     * looking up retry history; repeats the protected assignment gate for the target.
     * Terminal runs and internal retries are eligible, irrespective of command flags
     * or control leases. No assignment, retry or connector effect is created.
     *
     * Checks share an authority observation instant, not an atomic cross-query
     * supervision snapshot. Later navigation must acquire fresh exact-run access.
     * Storage failures propagate rather than fabricating successful absence.
     *
     * @throws AssignedRunNotFoundException when predecessor access is absent or denied,
     *   without inspecting its retry event or revealing successor existence.
     */
    fun get(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunRetrySuccessor =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val actor = HumanActorId(actorId)
            val at = clock.instant()
            if (authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at) == null) {
                throw AssignedRunNotFoundException()
            }
            val source =
                assignments.findAssigned(tenant, run, actor, at)
                    ?: throw AssignedRunNotFoundException()
            val replacement = retries.find(tenant, run)?.event?.replacementRunId
            val successor =
                replacement?.let { target ->
                    val current = assignments.findAssigned(tenant, target, actor, at)
                    current?.let { VisibleRetrySuccessor(target, it.revision) }
                }
            RunRetrySuccessor(
                at,
                source.revision,
                successor,
            )
        }
}
