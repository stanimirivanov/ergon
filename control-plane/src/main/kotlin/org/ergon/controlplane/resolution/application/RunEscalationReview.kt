package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Advisory observations, not authorization. Only RETRY_ATTEMPT_LIMIT_REACHED can permit confirmation. */
enum class RunEscalationReviewReason {
    DISABLED,
    RUN_NOT_FAILED,
    RETRY_BUDGET_AVAILABLE,
    RETRY_ATTEMPT_LIMIT_REACHED,
}

/**
 * Narrow current recovery coordinates; queue is server-selected.
 * No private evidence or work identity is disclosed.
 */
data class RunEscalationReview(
    val supported: Boolean,
    val observedAt: Instant,
    val supervisionRevision: Long,
    val runVersion: Long,
    val attemptNumber: Int,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val canEscalate: Boolean,
    val reason: RunEscalationReviewReason,
    val followUpQueueKey: String,
)

/** Reuses runtime policy without replanning an action or exposing case history. */
class RunEscalationReviewService(
    private val assignments: RunSupervisorAssignmentRepository,
    private val records: ResolutionRunEscalationRecords,
    private val policy: ResolutionRetryPolicy,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val enabled: Boolean,
) {
    /**
     * Reviews even disabled/terminal context only after current supervision and resolver authority.
     * @throws AssignedRunNotFoundException for absent or unauthorized context, without existence disclosure.
     */
    fun review(
        tenantId: UUID,
        runId: UUID,
        actorId: UUID,
    ): RunEscalationReview =
        transactions.required {
            val tenant = TenantId(tenantId)
            val run = ResolutionRunId(runId)
            val now = clock.instant()
            val current =
                assignments.findAssigned(tenant, run, HumanActorId(actorId), now)
                    ?: throw AssignedRunNotFoundException()
            val start = checkNotNull(records.runs.find(tenant, run)) { "assigned run start is absent" }.run
            val state = checkNotNull(records.transitions.findState(tenant, run)) { "assigned run state is absent" }
            val eligibility = policy.evaluate(start.attemptNumber)
            val reason =
                when {
                    !enabled -> {
                        RunEscalationReviewReason.DISABLED
                    }

                    state.state != ResolutionRunState.ACTION_FAILED || state.version != 1L -> {
                        RunEscalationReviewReason.RUN_NOT_FAILED
                    }

                    eligibility is ResolutionRetryEligibility.Eligible -> {
                        RunEscalationReviewReason.RETRY_BUDGET_AVAILABLE
                    }

                    else -> {
                        RunEscalationReviewReason.RETRY_ATTEMPT_LIMIT_REACHED
                    }
                }
            RunEscalationReview(
                enabled,
                now,
                current.revision.value,
                state.version,
                start.attemptNumber,
                eligibility.revision.value,
                eligibility.maximumAttempts,
                reason == RunEscalationReviewReason.RETRY_ATTEMPT_LIMIT_REACHED,
                reason,
                HumanFollowUpQueueKey.ACCESS_RESTORATION.value,
            )
        }
}
