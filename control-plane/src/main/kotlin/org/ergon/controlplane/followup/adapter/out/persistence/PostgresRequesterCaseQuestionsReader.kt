package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.INFORMATION_REQUESTS_LIMIT
import org.ergon.controlplane.followup.application.RequesterCaseQuestion
import org.ergon.controlplane.followup.application.RequesterCaseQuestionsPage
import org.ergon.controlplane.followup.application.RequesterCaseQuestionsReader
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Ownership and bounded public columns share one snapshot; no private question is loaded before authorization. */
@Repository
class PostgresRequesterCaseQuestionsReader(
    private val jdbc: JdbcClient,
) : RequesterCaseQuestionsReader {
    override fun find(
        tenant: TenantId,
        case: CaseId,
        actor: HumanActorId,
    ): RequesterCaseQuestionsPage? {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT q.request_id, q.question, q.requested_at, q.recorded_at
                    FROM case_requesters owner
                    LEFT JOIN LATERAL (
                        SELECT r.request_id, r.question, r.requested_at, r.recorded_at
                        FROM requester_information_requests r
                        WHERE r.tenant_id = owner.tenant_id AND r.case_id = owner.case_id
                        ORDER BY r.recorded_at DESC, r.request_id DESC
                        LIMIT :limit
                    ) q ON TRUE
                    WHERE owner.tenant_id = :tenant AND owner.case_id = :case AND owner.requester_actor_id = :actor
                    ORDER BY q.recorded_at DESC, q.request_id DESC
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("case", case.value)
                .param("actor", actor.value)
                .param("limit", INFORMATION_REQUESTS_LIMIT + 1)
                .query { row, _ ->
                    // The LEFT JOIN sentinel distinguishes an authorized empty case from a hidden case.
                    row.getObject("request_id", UUID::class.java)?.let { id ->
                        RequesterCaseQuestion(
                            id,
                            row.getString("question"),
                            row.getObject("requested_at", OffsetDateTime::class.java).toInstant(),
                            row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                        )
                    }
                }.list()
        if (rows.isEmpty()) return null
        val questions = rows.filterNotNull()
        return RequesterCaseQuestionsPage(
            questions.take(INFORMATION_REQUESTS_LIMIT),
            questions.size > INFORMATION_REQUESTS_LIMIT,
        )
    }
}
