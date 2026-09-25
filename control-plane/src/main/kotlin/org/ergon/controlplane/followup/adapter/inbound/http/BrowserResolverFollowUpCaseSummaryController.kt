package org.ergon.controlplane.followup.adapter.inbound.http

import org.ergon.controlplane.followup.application.ResolverFollowUpCaseSummary
import org.ergon.controlplane.followup.application.ResolverFollowUpCaseSummaryService
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Browser-session boundary for evidence and run context attached to owned work. */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary")
@ConditionalOnProperty(
    prefix = "ergon.security.browser-session",
    name = ["enabled"],
    havingValue = "true",
)
class BrowserResolverFollowUpCaseSummaryController(
    private val actors: AuthenticatedHumanActorResolver,
    private val summaries: ResolverFollowUpCaseSummaryService,
) {
    /**
     * Returns structured context only for active work owned by the session actor.
     *
     * The tenant and work-item path values select the resource; verified session
     * identity, durable claim ownership, and current resolver evidence authorize it.
     */
    @GetMapping
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable workItemId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): BrowserResolverFollowUpCaseSummaryResponse {
        val actor = actors.resolve(tenantId, principal)
        return summaries.get(tenantId, workItemId, actor.actor.id.value).toBrowserResponse()
    }
}

/** Owned-work coordinates and timing without resolver or authority attribution. */
data class BrowserResolverFollowUpSummaryResponse(
    val workItemId: UUID,
    val queueKey: String,
    val escalationReason: String,
    val openedAt: Instant,
    val claimedAt: Instant,
)

/** Current case header and exact contract revision used to interpret its evidence. */
data class BrowserResolverCaseSummaryResponse(
    val caseId: UUID,
    val goal: String,
    val status: String,
    val streamVersion: Long,
    val resolutionContract: BrowserResolverCaseContractResponse?,
)

/** Contract identity pinned to the case without its internal persistence metadata. */
data class BrowserResolverCaseContractResponse(
    val key: String,
    val revision: Int,
)

/** Attributable source observation in case stream order. */
data class BrowserResolverCaseObservationResponse(
    val streamVersion: Long,
    val eventType: String,
    val summary: String,
    val observationId: UUID,
    val originType: String,
    val provider: String,
    val reference: String?,
    val content: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
)

/** Immutable run inputs and current escalated state relevant to human resolution. */
data class BrowserResolverRunSummaryResponse(
    val runId: UUID,
    val caseEvidenceStreamVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val effectiveRisk: String,
    val requiredApproval: String,
    val attemptNumber: Int,
    val predecessorRunId: UUID?,
    val state: String,
    val stateVersion: Long,
    val stateUpdatedAt: Instant,
    val recordedAt: Instant,
)

/** Resolver-safe context for continuing one claimed follow-up. */
data class BrowserResolverFollowUpCaseSummaryResponse(
    val followUp: BrowserResolverFollowUpSummaryResponse,
    val case: BrowserResolverCaseSummaryResponse,
    val observations: List<BrowserResolverCaseObservationResponse>,
    val resolutionRun: BrowserResolverRunSummaryResponse,
)

private fun ResolverFollowUpCaseSummary.toBrowserResponse(): BrowserResolverFollowUpCaseSummaryResponse {
    val item = ownedWork.workItem.item
    val runSnapshot = run.run
    return BrowserResolverFollowUpCaseSummaryResponse(
        followUp =
            BrowserResolverFollowUpSummaryResponse(
                workItemId = item.id.value,
                queueKey = item.queueKey.value,
                escalationReason = item.reason.name,
                openedAt = item.openedAt,
                claimedAt = ownedWork.claim.claim.claimedAt,
            ),
        case =
            BrowserResolverCaseSummaryResponse(
                caseId = caseTimeline.caseId,
                goal = caseTimeline.goal,
                status = caseTimeline.status,
                streamVersion = caseTimeline.streamVersion,
                resolutionContract =
                    caseTimeline.resolutionContract?.let {
                        BrowserResolverCaseContractResponse(it.key, it.revision)
                    },
            ),
        observations =
            caseTimeline.entries.map { entry ->
                BrowserResolverCaseObservationResponse(
                    streamVersion = entry.streamVersion,
                    eventType = entry.eventType,
                    summary = entry.summary,
                    observationId = entry.observation.id,
                    originType = entry.observation.originType,
                    provider = entry.observation.provider,
                    reference = entry.observation.reference,
                    content = entry.observation.content,
                    occurredAt = entry.occurredAt,
                    recordedAt = entry.recordedAt,
                )
            },
        resolutionRun =
            BrowserResolverRunSummaryResponse(
                runId = runSnapshot.id.value,
                caseEvidenceStreamVersion = runSnapshot.caseStreamVersion,
                contractKey = runSnapshot.contract.key.value,
                contractRevision = runSnapshot.contract.revision.value,
                policyRevision = runSnapshot.policyRevision.value,
                stepId = runSnapshot.stepId.value,
                capability = runSnapshot.capability.value,
                effectiveRisk = runSnapshot.effectiveRisk.name,
                requiredApproval = runSnapshot.requiredApproval.name,
                attemptNumber = runSnapshot.attemptNumber,
                predecessorRunId = runSnapshot.predecessorRunId?.value,
                state = runState.state.name,
                stateVersion = runState.version,
                stateUpdatedAt = runState.updatedAt,
                recordedAt = run.recordedAt,
            ),
    )
}
