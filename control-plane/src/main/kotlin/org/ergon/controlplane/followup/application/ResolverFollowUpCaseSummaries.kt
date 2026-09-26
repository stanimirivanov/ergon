package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.controlplane.resolution.application.StoredResolutionRunEscalation
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.util.UUID

/** Evidence, failed execution, and handoff facts needed to understand one owned follow-up. */
data class ResolverFollowUpCaseSummary(
    val ownedWork: ResolverOwnedHumanFollowUpWork,
    val caseTimeline: CaseTimeline,
    val run: StoredResolutionRunStart,
    val runState: ResolutionRunStateSnapshot,
    val executionReceipt: StoredCapabilityInvocationReceipt,
    val escalation: StoredResolutionRunEscalation,
)

/** Hides work absence, different ownership, closed work, and lost authority behind one result. */
class ResolverFollowUpCaseSummaryNotFoundException(
    workItemId: UUID,
) : RuntimeException("resolver follow-up case summary for work item $workItemId was not found")

/** Builds resolver context only after proving current ownership and authority. */
@Suppress("LongParameterList") // Explicit ports preserve transactional read order; a dependency bag would hide it.
class ResolverFollowUpCaseSummaryService(
    private val claims: HumanFollowUpClaimRepository,
    private val cases: CaseTimelineRepository,
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val escalations: ResolutionRunEscalationRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Returns the case evidence and escalated run snapshot for owned active work.
     *
     * The ownership lookup is deliberately first. Protected case, run,
     * execution, and escalation records are not consulted when the
     * authenticated actor cannot see the work item.
     *
     * @throws ResolverFollowUpCaseSummaryNotFoundException when the work item is
     *   absent, closed, owned by another actor, or hidden by current authority.
     * @throws IllegalStateException when durable work references incomplete or
     *   contradictory case, run, execution, or escalation records.
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
            ResolverFollowUpCaseSummary(
                ownedWork,
                timeline,
                run,
                runState,
                executionReceipt,
                escalation,
            )
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
        check(workItem.openedAt == escalationEvent.occurredAt) {
            "owned follow-up opening time differs from its escalation"
        }
        check(!escalationEvent.occurredAt.isBefore(receipt.completedAt)) {
            "owned follow-up escalation predates its failed capability receipt"
        }
    }
}
