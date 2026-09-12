package org.ergon.controlplane.cases.application

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.TenantId
import java.util.UUID

class CaseQueryService(
    private val repository: CaseTimelineRepository,
) {
    fun timeline(
        tenantId: UUID,
        caseId: UUID,
    ): CaseTimeline =
        repository.find(TenantId(tenantId), CaseId(caseId))
            ?: throw CaseNotFoundException(tenantId, caseId)
}
