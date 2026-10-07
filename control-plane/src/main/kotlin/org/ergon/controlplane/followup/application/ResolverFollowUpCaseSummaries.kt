package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.FactCondition
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.controlplane.resolution.application.StoredResolutionRunCapabilityResult
import org.ergon.controlplane.resolution.application.StoredResolutionRunEscalation
import org.ergon.controlplane.resolution.application.StoredResolutionRunRetry
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.ergon.resolution.domain.toCurrentState
import java.time.Clock
import java.util.UUID

/** Evidence, failed attempts, handoff, and unassessed proof target for one owned follow-up. */
data class ResolverFollowUpCaseSummary(
    val ownedWork: ResolverOwnedHumanFollowUpWork,
    val caseTimeline: CaseTimeline,
    val run: StoredResolutionRunStart,
    val runState: ResolutionRunStateSnapshot,
    val executionReceipt: StoredCapabilityInvocationReceipt,
    val escalation: StoredResolutionRunEscalation,
    val runHistory: List<ResolverFollowUpRunAttempt>,
    val outcomeProof: FactCondition,
)

/** One durable failed attempt and its optional link to the next attempt. */
data class ResolverFollowUpRunAttempt(
    val run: StoredResolutionRunStart,
    val state: ResolutionRunStateSnapshot,
    val receipt: StoredCapabilityInvocationReceipt,
    val capabilityResult: StoredResolutionRunCapabilityResult,
    val retry: StoredResolutionRunRetry?,
)

/** Hides work absence, different ownership, closed work, and lost authority behind one result. */
class ResolverFollowUpCaseSummaryNotFoundException(
    workItemId: UUID,
) : RuntimeException("resolver follow-up case summary for work item $workItemId was not found")

/** Builds resolver context only after proving current ownership and authority. */
@Suppress("LongParameterList") // Explicit ports preserve ownership-first transactional read order.
class ResolverFollowUpCaseSummaryService(
    private val claims: HumanFollowUpClaimRepository,
    private val cases: CaseTimelineRepository,
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val escalations: ResolutionRunEscalationRepository,
    private val retries: ResolutionRunRetryRepository,
    private val contracts: ResolutionContractRevisionRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Returns case evidence, durable failed attempts, and the pinned proof target for owned active work.
     *
     * The ownership lookup is deliberately first. Protected case, run,
     * execution, escalation, retry, and contract records are not consulted when
     * the authenticated actor cannot see the work item.
     *
     * @throws ResolverFollowUpCaseSummaryNotFoundException when the work item is
     *   absent, closed, owned by another actor, or hidden by current authority.
     * @throws IllegalStateException when durable work references incomplete or
     *   contradictory case, run, execution, escalation, or contract records.
     */
    fun get(
        tenantId: UUID,
        workItemId: UUID,
        actorId: UUID,
    ): ResolverFollowUpCaseSummary =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedWorkItemId = HumanFollowUpWorkItemId(workItemId)
            val ownedWork =
                claims.findOwnedWorkForResolver(
                    scopedTenantId,
                    scopedWorkItemId,
                    HumanActorId(actorId),
                    clock.instant(),
                ) ?: throw ResolverFollowUpCaseSummaryNotFoundException(workItemId)
            val workItem = ownedWork.workItem.item
            val timeline =
                checkNotNull(cases.find(scopedTenantId, workItem.caseId)) {
                    "owned human follow-up references a missing case timeline"
                }
            val run =
                checkNotNull(runs.find(scopedTenantId, workItem.runId)) {
                    "owned human follow-up references a missing resolution run"
                }
            val runState =
                checkNotNull(transitions.findState(scopedTenantId, workItem.runId)) {
                    "owned human follow-up references a missing resolution run state"
                }
            requireConsistentCaseAndRun(workItem.caseId, workItem.runId, timeline, run, runState)
            val executionReceipt =
                checkNotNull(receipts.findByRun(scopedTenantId, workItem.runId)) {
                    "owned human follow-up references a missing capability receipt"
                }
            val escalation =
                checkNotNull(escalations.find(scopedTenantId, workItem.runId)) {
                    "owned human follow-up references a missing escalation event"
                }
            requireConsistentHandoff(workItem, run, executionReceipt, escalation)
            val history = loadRunHistory(scopedTenantId, run, runState, executionReceipt, escalation)
            val contract =
                checkNotNull(contracts.find(scopedTenantId, run.run.contract.key, run.run.contract.revision)) {
                    "owned follow-up resolution contract revision is missing"
                }.contract
            check(contract.key == run.run.contract.key && contract.revision == run.run.contract.revision) {
                "owned follow-up resolution contract identity is inconsistent"
            }
            ResolverFollowUpCaseSummary(
                ownedWork,
                timeline,
                run,
                runState,
                executionReceipt,
                escalation,
                history,
                contract.outcomeProof,
            )
        }

    private fun loadRunHistory(
        tenantId: TenantId,
        escalatedRun: StoredResolutionRunStart,
        escalatedState: ResolutionRunStateSnapshot,
        escalatedReceipt: StoredCapabilityInvocationReceipt,
        escalation: StoredResolutionRunEscalation,
    ): List<ResolverFollowUpRunAttempt> {
        val reverse = mutableListOf<ResolverFollowUpRunAttempt>()
        val seen = mutableSetOf<ResolutionRunId>()
        var run = escalatedRun
        var successor: StoredResolutionRunStart? = null
        while (true) {
            val snapshot = run.run
            check(seen.add(snapshot.id)) { "owned follow-up run history contains a cycle" }
            check(successor == null || successor.run.predecessorRunId == snapshot.id) {
                "owned follow-up predecessor run identity is inconsistent"
            }
            check(successor == null || snapshot.attemptNumber + 1 == successor.run.attemptNumber) {
                "owned follow-up run history skips an attempt"
            }
            check(reverse.size < escalation.event.retryDenial.maximumAttempts) {
                "owned follow-up run history exceeds its retry ceiling"
            }
            val state =
                if (successor == null) {
                    escalatedState
                } else {
                    checkNotNull(transitions.findState(tenantId, snapshot.id)) {
                        "owned follow-up predecessor run state is missing"
                    }
                }
            val receipt =
                if (successor == null) {
                    escalatedReceipt
                } else {
                    checkNotNull(receipts.findByRun(tenantId, snapshot.id)) {
                        "owned follow-up predecessor capability receipt is missing"
                    }
                }
            requireConsistentAttempt(escalatedRun.run, run, state, receipt)
            val result = loadFailedResult(tenantId, snapshot, receipt)
            val retry = loadSuccessorRetry(tenantId, snapshot.id, successor, result)
            requireFinalTransition(state, retry, escalation)
            reverse += ResolverFollowUpRunAttempt(run, state, receipt, result, retry)
            val predecessorId = snapshot.predecessorRunId ?: break
            successor = run
            run =
                checkNotNull(runs.find(tenantId, predecessorId)) {
                    "owned follow-up predecessor run is missing"
                }
        }
        check(reverse.size == escalatedRun.run.attemptNumber && run.run.attemptNumber == 1) {
            "owned follow-up run history does not cover every attempt"
        }
        return reverse.reversed()
    }

    private fun loadFailedResult(
        tenantId: TenantId,
        run: ResolutionRunStart,
        receipt: StoredCapabilityInvocationReceipt,
    ): StoredResolutionRunCapabilityResult {
        val result =
            checkNotNull(transitions.findByReceipt(tenantId, receipt.receipt.authorizationConsumptionId)) {
                "owned follow-up capability result event is missing"
            }
        check(result.event.runId == run.id && result.event.sequence == 1L) {
            "owned follow-up capability result belongs to another attempt"
        }
        check(
            result.event.type == ResolutionRunEventType.CAPABILITY_FAILED &&
                result.event.fromState == run.initialState.toCurrentState() &&
                result.event.receiptOutcome == CapabilityInvocationOutcome.FAILED &&
                result.event.authorizationConsumptionId == receipt.receipt.authorizationConsumptionId &&
                result.event.toState == ResolutionRunState.ACTION_FAILED &&
                result.event.occurredAt == receipt.receipt.completedAt,
        ) { "owned follow-up capability result contradicts its failed receipt" }
        return result
    }

    private fun loadSuccessorRetry(
        tenantId: TenantId,
        runId: ResolutionRunId,
        successor: StoredResolutionRunStart?,
        result: StoredResolutionRunCapabilityResult,
    ): StoredResolutionRunRetry? =
        successor?.let { next ->
            checkNotNull(retries.find(tenantId, runId)) {
                "owned follow-up predecessor retry event is missing"
            }.also {
                check(it.event.failedRunId == runId && it.event.replacementRunId == next.run.id) {
                    "owned follow-up retry link contradicts its successor"
                }
                check(it.event.occurredAt >= result.event.occurredAt) {
                    "owned follow-up retry predates failed execution"
                }
            }
        }

    private fun requireFinalTransition(
        state: ResolutionRunStateSnapshot,
        retry: StoredResolutionRunRetry?,
        escalation: StoredResolutionRunEscalation,
    ) {
        val matches =
            if (retry == null) {
                state.state == ResolutionRunState.ESCALATED &&
                    state.version == escalation.event.sequence &&
                    state.updatedAt == escalation.event.occurredAt
            } else {
                state.state == ResolutionRunState.SUPERSEDED &&
                    state.version == retry.event.sequence &&
                    state.updatedAt == retry.event.occurredAt
            }
        check(matches) { "owned follow-up run state contradicts its final transition" }
    }

    private fun requireConsistentAttempt(
        escalatedRun: ResolutionRunStart,
        storedRun: StoredResolutionRunStart,
        state: ResolutionRunStateSnapshot,
        receipt: StoredCapabilityInvocationReceipt,
    ) {
        val run = storedRun.run
        check(
            run.caseId == escalatedRun.caseId && run.contract == escalatedRun.contract &&
                run.stepId == escalatedRun.stepId && run.capability == escalatedRun.capability,
        ) { "owned follow-up attempt changes its case or operation" }
        check(
            state.runId == run.id && receipt.receipt.runId == run.id &&
                receipt.receipt.caseId == run.caseId && receipt.receipt.policyRevision == run.policyRevision &&
                receipt.receipt.stepId == run.stepId && receipt.receipt.capability == run.capability &&
                receipt.receipt.outcome == CapabilityInvocationOutcome.FAILED,
        ) { "owned follow-up attempt has inconsistent failed execution" }
    }

    private fun requireConsistentCaseAndRun(
        caseId: CaseId,
        runId: ResolutionRunId,
        timeline: CaseTimeline,
        run: StoredResolutionRunStart,
        runState: ResolutionRunStateSnapshot,
    ) {
        check(timeline.caseId == caseId.value) { "owned follow-up case timeline identity is inconsistent" }
        check(run.run.caseId == caseId) { "owned follow-up resolution run belongs to another case" }
        check(run.run.id == runId) { "owned follow-up references another resolution run" }
        check(runState.runId == runId) { "owned follow-up run state belongs to another run" }
        check(timeline.streamVersion >= run.run.caseStreamVersion) {
            "owned follow-up case timeline predates its resolution run"
        }
        check(
            timeline.resolutionContract?.let {
                it.key == run.run.contract.key.value && it.revision == run.run.contract.revision.value
            } == true,
        ) { "owned follow-up case contract differs from its resolution run" }
        check(runState.state == ResolutionRunState.ESCALATED) {
            "owned follow-up resolution run is not escalated"
        }
    }

    private fun requireConsistentHandoff(
        workItem: HumanFollowUpWorkItem,
        run: StoredResolutionRunStart,
        executionReceipt: StoredCapabilityInvocationReceipt,
        escalation: StoredResolutionRunEscalation,
    ) {
        val runSnapshot = run.run
        val receipt = executionReceipt.receipt
        val escalationEvent = escalation.event
        check(receipt.runId == runSnapshot.id && receipt.caseId == runSnapshot.caseId) {
            "owned follow-up capability receipt belongs to another run"
        }
        check(
            receipt.policyRevision == runSnapshot.policyRevision &&
                receipt.stepId == runSnapshot.stepId &&
                receipt.capability == runSnapshot.capability,
        ) { "owned follow-up capability receipt differs from its resolution run" }
        check(receipt.outcome == CapabilityInvocationOutcome.FAILED) {
            "owned follow-up capability receipt is not failed"
        }
        check(escalationEvent.id == workItem.escalationEventId && escalationEvent.runId == runSnapshot.id) {
            "owned follow-up escalation event identity is inconsistent"
        }
        check(escalationEvent.reason == workItem.reason) {
            "owned follow-up escalation reason is inconsistent"
        }
        check(escalationEvent.retryDenial.sourceAttemptNumber == runSnapshot.attemptNumber) {
            "owned follow-up escalation retry decision belongs to another attempt"
        }
        check(escalationEvent.retryDenial.maximumAttempts == runSnapshot.attemptNumber) {
            "owned follow-up escalation retry ceiling differs from the final attempt"
        }
        check(workItem.openedAt == escalationEvent.occurredAt) {
            "owned follow-up opening time differs from its escalation"
        }
        check(!escalationEvent.occurredAt.isBefore(receipt.completedAt)) {
            "owned follow-up escalation predates its failed capability receipt"
        }
    }
}
