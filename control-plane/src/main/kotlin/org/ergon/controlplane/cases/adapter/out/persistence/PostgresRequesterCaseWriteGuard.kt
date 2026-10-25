package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.RequesterCaseWriteGuard
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Uses the caller's work-then-case transaction; the immutable binding needs no separate lock. */
@Repository
class PostgresRequesterCaseWriteGuard(
    private val jdbc: JdbcClient,
) : RequesterCaseWriteGuard {
    override fun lockOpenBoundCase(
        tenant: TenantId,
        caseId: CaseId,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT 1 FROM cases c
                JOIN case_requesters r ON r.tenant_id = c.tenant_id AND r.case_id = c.case_id
                WHERE c.tenant_id = :tenant AND c.case_id = :case AND c.status = 'OPEN'
                FOR UPDATE OF c
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("case", caseId.value)
            .query(Int::class.java)
            .optional()
            .isPresent
}
