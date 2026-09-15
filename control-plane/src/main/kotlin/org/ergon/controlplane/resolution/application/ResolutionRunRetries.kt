package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunRetryStarted
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Durable successor and immutable predecessor transition returned by a retry command. */
data class ResolutionRunRetryExecution(
    val replacementRun: StoredResolutionRunStart,
    val recording: ResolutionRunRetryRecording,
)

/** Explicit retry event paired with its database recording instant. */
data class StoredResolutionRunRetry(
    val event: ResolutionRunRetryStarted,
    val recordedAt: Instant,
)

/** Result of starting or replaying a successor for one failed run. */
data class ResolutionRunRetryRecording(
    val storedEvent: StoredResolutionRunRetry,
    val failedRunState: ResolutionRunStateSnapshot,
    val created: Boolean,
)

/** Durable append and replay boundary for explicit failed-run successors. */
interface ResolutionRunRetryRepository {
    /** @return the retry event already recorded for [runId], or `null` before retry. */
    fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunRetry?

    /**
     * Appends [event] and marks its failed predecessor superseded atomically.
     *
     * The caller must hold the predecessor state lock and must already have
     * inserted the replacement run in the same transaction.
     */
    fun append(
        tenantId: TenantId,
        event: ResolutionRunRetryStarted,
    ): ResolutionRunRetryRecording
}

/** Authoritative ports consulted together while creating an explicit retry. */
data class ResolutionRunRetryRecords(
    val planning: ResolutionPlanner,
    val runs: ResolutionRunRepository,
    val transitions: ResolutionRunTransitionRepository,
    val retries: ResolutionRunRetryRepository,
)

/** Signals that the run is not an unsuperseded failed attempt. */
class ResolutionRunRetryStateException(
    val state: String,
) : RuntimeException("resolution run cannot retry while state is $state")

/** Signals that current planning no longer describes the operation that failed. */
class ResolutionRunRetryPlanChangedException :
    RuntimeException("current plan changes the failed contract, step, or capability; retry requires the same operation")

/** Starts explicit successor attempts without reusing or executing previous authorization. */
class ResolutionRunRetryService(
    private val records: ResolutionRunRetryRecords,
    private val runIdentities: ResolutionRunIdentityGenerator,
    private val eventIdentities: ResolutionRunEventIdentityGenerator,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Supersedes one failed run and starts its sole direct successor atomically.
     *
     * Current evidence and policy are recalculated at [expectedCaseVersion]. The
     * new attempt retains operation meaning but begins with fresh requirements;
     * no approval, grant, consumption, receipt, or connector call is copied.
     * Replay returns the original successor even if the case has since advanced.
     *
     * @throws ResolutionRunNotFoundException when [failedRunId] is absent from [tenantId].
     * @throws ResolutionRunRetryStateException unless the run is `ACTION_FAILED`.
     * @throws ResolutionRunRetryPlanChangedException when the pinned operation changed.
     * @throws ResolutionRunNotReadyException when current evidence is not ready.
     * @throws ResolutionRunPolicyDeniedException when current policy denies the operation.
     * @throws org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
     *   when the supplied positive case version is stale or changes before insert.
     */
    fun retry(
        tenantId: UUID,
        failedRunId: UUID,
        expectedCaseVersion: Long,
    ): ResolutionRunRetryExecution {
        require(expectedCaseVersion > 0) { "If-Match version must be positive" }
        return transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedRunId = ResolutionRunId(failedRunId)
            val failedRun =
                records.runs.find(scopedTenantId, scopedRunId)?.run
                    ?: throw ResolutionRunNotFoundException(failedRunId)
            val state =
                checkNotNull(records.transitions.lockState(scopedTenantId, scopedRunId)) {
                    "resolution run current-state projection is missing"
                }
            records.retries.find(scopedTenantId, scopedRunId)?.let { existing ->
                val replacement =
                    checkNotNull(records.runs.find(scopedTenantId, existing.event.replacementRunId)) {
                        "resolution run retry references an absent replacement"
                    }
                return@required ResolutionRunRetryExecution(
                    replacement,
                    ResolutionRunRetryRecording(existing, state, created = false),
                )
            }
            if (state.state != ResolutionRunState.ACTION_FAILED || state.version != 1L) {
                throw ResolutionRunRetryStateException(state.state.name)
            }
            val plan = records.planning.plan(tenantId, failedRun.caseId.value).toRunPlan(expectedCaseVersion)
            plan.requireSameOperation(failedRun)
            val (replacement, event) = createRetry(failedRun, state, plan)
            val storedRun = records.runs.createRetry(scopedTenantId, replacement)
            val recording = records.retries.append(scopedTenantId, event)
            ResolutionRunRetryExecution(storedRun, recording)
        }
    }

    private fun createRetry(
        failedRun: ResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        plan: ResolutionRunPlan,
    ): Pair<ResolutionRunStart, ResolutionRunRetryStarted> =
        try {
            val replacement = ResolutionRunStart.retry(runIdentities.next(), failedRun, plan)
            val event =
                ResolutionRunRetryStarted.start(
                    eventIdentities.next(),
                    failedRun,
                    state,
                    replacement,
                    clock.instant(),
                )
            replacement to event
        } catch (exception: IllegalArgumentException) {
            // All inputs were validated durable records; disagreement is an internal integrity failure.
            throw IllegalStateException("stored resolution retry sources are inconsistent", exception)
        }
}

private fun ResolutionRunPlan.requireSameOperation(failedRun: ResolutionRunStart) {
    if (contract != failedRun.contract || stepId != failedRun.stepId || capability != failedRun.capability) {
        throw ResolutionRunRetryPlanChangedException()
    }
}
