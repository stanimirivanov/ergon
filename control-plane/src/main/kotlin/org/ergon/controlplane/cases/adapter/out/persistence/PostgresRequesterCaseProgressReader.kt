package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.CaseStatus
import org.ergon.controlplane.cases.application.RequesterCaseProgress
import org.ergon.controlplane.cases.application.RequesterCaseProgressReader
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** A single PostgreSQL snapshot binds authorization to the exact public columns returned. */
@Repository
class PostgresRequesterCaseProgressReader(
    private val jdbc: JdbcClient,
) : RequesterCaseProgressReader {
    override fun find(
        tenantId: TenantId,
        actorId: HumanActorId,
        caseId: CaseId,
    ): RequesterCaseProgress? =
        jdbc
            .sql(
                """
                SELECT c.case_id, c.goal, c.status, c.opened_at, c.updated_at
                FROM cases c
                INNER JOIN case_requesters r ON r.tenant_id = c.tenant_id AND r.case_id = c.case_id
                WHERE c.tenant_id = :tenant AND c.case_id = :case AND r.requester_actor_id = :actor
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("case", caseId.value)
            .param("actor", actorId.value)
            .query { row, _ ->
                RequesterCaseProgress(
                    caseId = row.getObject("case_id", UUID::class.java),
                    goal = row.getString("goal"),
                    status = CaseStatus.valueOf(row.getString("status")),
                    openedAt = row.getObject("opened_at", OffsetDateTime::class.java).toInstant(),
                    updatedAt = row.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)
}
