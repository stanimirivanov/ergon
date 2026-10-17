package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Stable eligibility explanations; these are observations, never retry or assignment permissions. */
enum class RunRetryReviewReason {
    DISABLED,
    RUN_NOT_FAILED,
    ATTEMPT_LIMIT_REACHED,
    PLAN_NOT_READY,
    PLAN_DENIED,
    OPERATION_CHANGED,
}

/** A narrow planning snapshot. No raw new evidence, authority IDs or unrelated successor identity is disclosed. */
data class RunRetryReview(
    val supported: Boolean,
    val observedAt: Instant,
    val supervisionRevision: Long,
    val runVersion: Long,
    val attemptNumber: Int,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val canRetry: Boolean,
    val reason: RunRetryReviewReason?,
    val plan: ReviewedRunRetryPlan?,
)

internal data class EvaluatedRunRetry(
    val eligibility: ResolutionRetryEligibility,
    val plan: ResolutionRunPlan?,
    val reason: RunRetryReviewReason?,
)

/** Authority-first private review; runtime policy and planning remain owned by their existing capabilities. */
class RunRetryReviewService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val records: ResolutionRunRetryRecords,
    private val policy: ResolutionRetryPolicy,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Returns bounded eligibility after the exact supervision/resolver gate,
     * even when commands are disabled.
     * @throws AssignedRunNotFoundException for unavailable or unauthorized run context,
     * without disclosing its existence.
     */
    fun review(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunRetryReview =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val now = clock.instant()
            val current =
                assignments.findAssigned(tenant, run, HumanActorId(actorId), now)
                    ?: throw AssignedRunNotFoundException()
            val start = checkNotNull(records.runs.find(tenant, run)) { "assigned run start is absent" }.run
            val state = checkNotNull(records.transitions.findState(tenant, run)) { "assigned run state is absent" }
            val evaluated = evaluate(tenant, start, state)
            val reason = if (!enabled) RunRetryReviewReason.DISABLED else evaluated.reason
            RunRetryReview(
                enabled,
                now,
                current.revision.value,
                state.version,
                start.attemptNumber,
                evaluated.eligibility.revision.value,
                evaluated.eligibility.maximumAttempts,
                reason == null,
                reason,
                evaluated.plan?.let(ReviewedRunRetryPlan::from),
            )
        }

    internal fun evaluate(
        tenant: TenantId,
        start: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
    ): EvaluatedRunRetry {
        val eligibility = policy.evaluate(start.attemptNumber)
        return when {
            state.state != ResolutionRunState.ACTION_FAILED || state.version != 1L -> {
                EvaluatedRunRetry(eligibility, null, RunRetryReviewReason.RUN_NOT_FAILED)
            }

            eligibility is ResolutionRetryEligibility.Denied -> {
                EvaluatedRunRetry(eligibility, null, RunRetryReviewReason.ATTEMPT_LIMIT_REACHED)
            }

            else -> {
                evaluatePlan(tenant, start, eligibility)
            }
        }
    }

    private fun evaluatePlan(
        tenant: TenantId,
        start: ResolutionRunStart,
        eligibility: ResolutionRetryEligibility,
    ): EvaluatedRunRetry =
        try {
            val planning = records.planning.plan(tenant.value, start.caseId.value)
            val plan = planning.toRunPlan(planning.readiness.caseStreamVersion)
            if (plan.contract != start.contract || plan.stepId != start.stepId || plan.capability != start.capability) {
                EvaluatedRunRetry(eligibility, null, RunRetryReviewReason.OPERATION_CHANGED)
            } else {
                EvaluatedRunRetry(eligibility, plan, null)
            }
        } catch (_: ResolutionRunNotReadyException) {
            EvaluatedRunRetry(eligibility, null, RunRetryReviewReason.PLAN_NOT_READY)
        } catch (_: ResolutionRunPolicyDeniedException) {
            EvaluatedRunRetry(eligibility, null, RunRetryReviewReason.PLAN_DENIED)
        }
}
