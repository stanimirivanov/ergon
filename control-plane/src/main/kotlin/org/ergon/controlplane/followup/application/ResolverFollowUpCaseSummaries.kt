package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Clock
import java.util.UUID

/** Evidence and run state needed to understand one resolver-owned follow-up. */
data class ResolverFollowUpCaseSummary(
    val ownedWork: ResolverOwnedHumanFollowUpWork,
    val caseTimeline: CaseTimeline,
    val run: StoredResolutionRunStart,
    val runState: ResolutionRunStateSnapshot,
)

/** Hides work absence, different ownership, closed work, and lost authority behind one result. */
class ResolverFollowUpCaseSummaryNotFoundException(
    workItemId: UUID,
) : RuntimeException("resolver follow-up case summary for work item $workItemId was not found")

/** Builds resolver context only after proving current ownership and authority. */
class ResolverFollowUpCaseSummaryService(
    private val claims: HumanFollowUpClaimRepository,
    private val cases: CaseTimelineRepository,
    private val runs: ResolutionRunRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Returns the case evidence and escalated run snapshot for owned active work.
     *
     * The ownership lookup is deliberately first. Case and run records are not
     * consulted when the authenticated actor cannot see the work item.
     *
     * @throws ResolverFollowUpCaseSummaryNotFoundException when the work item is
     *   absent, closed, owned by another actor, or hidden by current authority.
     * @throws IllegalStateException when durable work references incomplete or
     *   contradictory case or run projections.
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
            requireConsistentSources(workItem.caseId, workItem.runId, timeline, run, runState)
            ResolverFollowUpCaseSummary(ownedWork, timeline, run, runState)
        }

    private fun requireConsistentSources(
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
}
