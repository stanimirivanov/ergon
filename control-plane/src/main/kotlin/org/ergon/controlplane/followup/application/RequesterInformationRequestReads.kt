package org.ergon.controlplane.followup.application

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.RequesterCaseNotFoundException
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Newest-first history; [truncated] means older questions exist, not that any were delivered or answered. */
data class OwnedInformationRequestsPage(
    val items: List<OwnedInformationRequest>,
    val truncated: Boolean,
)

/** History view, not a write receipt or access grant; null [response] means no answer in this read snapshot. */
data class OwnedInformationRequest(
    val stored: StoredRequesterInformationRequest,
    val response: RecordedQuestionResponse?,
)

/**
 * Narrow immutable answer projection, preserving exact text and persisted timestamp precision.
 * [respondedAt] is the server command time; [recordedAt] is database recording time, not delivery time.
 * Text remains untrusted, never consent, verified evidence or proof. No private actor attribution is carried.
 */
data class RecordedQuestionResponse(
    val responseId: UUID,
    val answer: String,
    val respondedAt: Instant,
    val recordedAt: Instant,
)

/** A requester-safe question, deliberately excluding resolver/work/claim and provider attribution. */
data class RequesterCaseQuestion(
    val requestId: UUID,
    val question: String,
    val requestedAt: Instant,
    val recordedAt: Instant,
    /** Null means no answer was visible in the same snapshot as this question. */
    val response: RecordedQuestionResponse?,
)

/** Newest-first, at most 100 questions across this case's work items; text remains untrusted. */
data class RequesterCaseQuestionsPage(
    val items: List<RequesterCaseQuestion>,
    val truncated: Boolean,
)

fun interface OwnedInformationRequestsReader {
    /**
     * Returns at most positive [limit] rows, newest (recordedAt, requestId) first, or an empty list.
     * Caller holds the active work lock in a transaction and establishes current ownership/authority.
     * Each optional answer is joined by tenant/question in the same snapshot; answers do not affect ordering.
     * Storage failures propagate; absence never substitutes for an access decision.
     */
    fun latest(
        tenant: TenantId,
        work: HumanFollowUpWorkItemId,
        limit: Int,
    ): List<OwnedInformationRequest>
}

fun interface RequesterCaseQuestionsReader {
    /**
     * Binds exact tenant/case/requester ownership, questions and narrow optional answers in one snapshot.
     * Returns null for every invisible case, but an empty page for a visible case without questions.
     * Lifecycle and resolver ownership do not remove requester access. Infrastructure failures propagate.
     */
    fun find(
        tenant: TenantId,
        case: CaseId,
        actor: HumanActorId,
    ): RequesterCaseQuestionsPage?
}

/** Serializes private history disclosure with ownership changes; no state or revisions advance. */
class OwnedInformationRequestsService(
    private val claims: HumanFollowUpClaimRepository,
    private val cases: RequesterCaseWriteGuard,
    private val reader: OwnedInformationRequestsReader,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Includes predecessor-authored questions, but only for the current owner of active work and an OPEN bound case.
     * [actorId] is supplied by the verified session, not URL parameters.
     * @throws RequesterInformationRequestNotFoundException for every inaccessible work/case or expired authority.
     */
    fun list(
        tenantId: UUID,
        workItemId: UUID,
        actorId: UUID,
    ): OwnedInformationRequestsPage =
        transactions.required {
            val tenant = TenantId(tenantId)
            val work = HumanFollowUpWorkItemId(workItemId)
            val actor = HumanActorId(actorId)
            if (!claims.lockOpenWorkItem(tenant, work)) throw RequesterInformationRequestNotFoundException()
            val owned = requireOwner(tenant, work, actor)
            // Same work-then-case order as the command; evaluate expiry again after any case-lock wait.
            if (!cases.lockOpenBoundCase(tenant, owned.workItem.item.caseId)) {
                throw RequesterInformationRequestNotFoundException()
            }
            requireOwner(tenant, work, actor)
            val rows = reader.latest(tenant, work, INFORMATION_REQUESTS_LIMIT + 1)
            requireOwner(tenant, work, actor)
            OwnedInformationRequestsPage(rows.take(INFORMATION_REQUESTS_LIMIT), rows.size > INFORMATION_REQUESTS_LIMIT)
        }

    private fun requireOwner(
        tenant: TenantId,
        work: HumanFollowUpWorkItemId,
        actor: HumanActorId,
    ): ResolverOwnedHumanFollowUpWork =
        claims.findOwnedWorkForResolver(tenant, work, actor, clock.instant())
            ?: throw RequesterInformationRequestNotFoundException()
}

/** Requester access is explicit case ownership, never resolver authority or possession of a request UUID. */
class RequesterCaseQuestionsService(
    private val reader: RequesterCaseQuestionsReader,
) {
    /**
     * [actorId] must originate from the verified tenant session.
     * Reads do not acknowledge delivery or answer a question.
     * @throws RequesterCaseNotFoundException equally for absent, unbound, foreign-owned and cross-tenant cases.
     */
    fun list(
        tenantId: UUID,
        caseId: UUID,
        actorId: UUID,
    ): RequesterCaseQuestionsPage {
        val page = reader.find(TenantId(tenantId), CaseId(caseId), HumanActorId(actorId))
        return page ?: throw RequesterCaseNotFoundException()
    }
}

internal const val INFORMATION_REQUESTS_LIMIT = 100
