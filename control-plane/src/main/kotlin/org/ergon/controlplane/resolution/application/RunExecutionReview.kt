package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Observed eligibility only. Commands recheck all gates; another actor's command identity is never disclosed. */
data class RunExecutionReview(
    val supported: Boolean,
    val observedAt: Instant,
    val supervisionRevision: Long,
    val runVersion: Long,
    val requestId: UUID?,
    val decisionId: UUID?,
    val commandId: UUID?,
    val canExecute: Boolean,
    val canResume: Boolean,
)

/** Composes the existing authority-first Console read with narrowly scoped execution eligibility. */
class RunExecutionReviewService(
    private val console: AssignedRunConsoleService,
    private val commands: RunExecutionCommandRepository,
    private val routes: CapabilityRouteRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /** Current supervisor access is required even for disabled/read-only review; never creates authority. */
    fun review(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunExecutionReview =
        transactions.required {
            val detail = console.get(tenantId, runId, actorId)
            val tenant = TenantId(tenantId)
            val latest = detail.approvalReview.latest
            val decision = latest?.decision
            val request = latest?.request?.request
            val stored = commands.findByRun(tenant, ResolutionRunId(runId))
            val at = clock.instant()
            val available =
                decision?.let {
                    val valid = request != null && !at.isBefore(request.requestedAt) && at.isBefore(request.expiresAt)
                    valid && it.outcome == ApprovalDecisionOutcome.APPROVED &&
                        !commands.hasPriorAuthorization(tenant, ResolutionRunId(runId)) &&
                        routes.find(tenant, detail.start.run.capability) != null
                } ?: false
            val initial = detail.state.version == 0L
            RunExecutionReview(
                enabled,
                at,
                detail.supervisionRevision.value,
                detail.state.version,
                request?.id?.value,
                decision?.decisionId,
                stored?.takeIf { it.command.actorId == actorId }?.command?.commandId,
                enabled && initial && stored == null && available,
                enabled && initial && stored?.command?.actorId == actorId,
            )
        }
}
