package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.TenantId
import java.util.UUID

/** Resolves tenant-scoped case queries without exposing persistence details. */
class CaseQueryService(
    private val repository: CaseTimelineRepository,
    private val accountAccessFacts: CaseAccountAccessFactRepository,
) {
    /**
     * Returns the complete case timeline.
     *
     * @throws CaseNotFoundException when the case is absent from the tenant boundary.
     */
    fun timeline(
        tenantId: UUID,
        caseId: UUID,
    ): CaseTimeline =
        repository.find(TenantId(tenantId), CaseId(caseId))
            ?: throw CaseNotFoundException(tenantId, caseId)

    /**
     * Returns account-access facts in the order they were bound.
     *
     * @throws CaseNotFoundException when the case is absent from the tenant boundary.
     */
    fun accountAccessFacts(
        tenantId: UUID,
        caseId: UUID,
    ): CaseAccountAccessFacts =
        accountAccessFacts.find(TenantId(tenantId), CaseId(caseId))
            ?: throw CaseNotFoundException(tenantId, caseId)
}
