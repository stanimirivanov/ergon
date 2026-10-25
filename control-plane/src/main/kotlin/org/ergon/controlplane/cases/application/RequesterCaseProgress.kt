package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.CaseStatus
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

/**
 * Narrow case projection, deliberately excluding source material and runtime/authority data.
 * OPEN does not describe run or human-work state; only existing accepted proof closes a case.
 * Timestamps describe case projection changes, not binding or proof recording times.
 */
data class RequesterCaseProgress(
    val caseId: UUID,
    val goal: String,
    val status: CaseStatus,
    val openedAt: Instant,
    val updatedAt: Instant,
)

/** Consumer-owned read boundary; approval attestations must never substitute for requester ownership. */
fun interface RequesterCaseProgressReader {
    /**
     * Reads ownership and public projection in one snapshot, scoped to all three identifiers.
     * Returns null equally for absent, unattributed, foreign-owned and cross-tenant cases.
     * Infrastructure failures propagate rather than becoming absence.
     */
    fun find(
        tenantId: TenantId,
        actorId: HumanActorId,
        caseId: CaseId,
    ): RequesterCaseProgress?
}

/** Applies requester non-disclosure without loading a private case timeline into the read path. */
class RequesterCaseProgressService(
    private val reader: RequesterCaseProgressReader,
) {
    /**
     * [actorId] must come from the verified tenant session, never request parameters or approval claims.
     * Reads neither acquire resolver authority nor mutate case, run, binding or proof state.
     * @throws RequesterCaseNotFoundException for every non-visible case, without distinguishing existence.
     */
    fun progress(
        tenantId: UUID,
        actorId: UUID,
        caseId: UUID,
    ): RequesterCaseProgress =
        reader.find(TenantId(tenantId), HumanActorId(actorId), CaseId(caseId)) ?: throw RequesterCaseNotFoundException()
}

/** The same public absence for a missing case and a case outside the current requester's ownership. */
class RequesterCaseNotFoundException : RuntimeException("Requester case not found")
