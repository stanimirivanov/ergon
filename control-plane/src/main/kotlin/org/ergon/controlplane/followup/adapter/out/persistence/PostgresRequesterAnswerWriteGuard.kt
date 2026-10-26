package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.RequesterAnswerWriteGuard
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Locks only the case; immutable question/binding parents need no lock or copied attribution. */
@Repository
class PostgresRequesterAnswerWriteGuard(
    private val jdbc: JdbcClient,
) : RequesterAnswerWriteGuard {
    override fun lockQuestion(
        tenant: TenantId,
        case: CaseId,
        request: RequesterInformationRequestId,
        actor: HumanActorId,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT 1 FROM cases c
                JOIN case_requesters r ON r.tenant_id = c.tenant_id AND r.case_id = c.case_id
                JOIN requester_information_requests q ON q.tenant_id = c.tenant_id AND q.case_id = c.case_id
                WHERE c.tenant_id = :tenant AND c.case_id = :case AND c.status = 'OPEN'
                    AND r.requester_actor_id = :actor AND q.request_id = :request
                FOR UPDATE OF c
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("case", case.value)
            .param("actor", actor.value)
            .param("request", request.value)
            .query(Int::class.java)
            .optional()
            .isPresent
}
