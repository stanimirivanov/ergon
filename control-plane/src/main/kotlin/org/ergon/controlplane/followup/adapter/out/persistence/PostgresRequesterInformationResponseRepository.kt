package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.RequesterInformationResponse
import org.ergon.controlplane.followup.application.RequesterInformationResponseRecording
import org.ergon.controlplane.followup.application.RequesterInformationResponseRepository
import org.ergon.controlplane.followup.application.StoredRequesterInformationResponse
import org.ergon.followup.domain.RequesterInformationAnswer
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.followup.domain.RequesterInformationResponseId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Joins immutable parents rather than persisting caller-selected requester/case attribution. */
@Repository
class PostgresRequesterInformationResponseRepository(
    private val jdbc: JdbcClient,
) : RequesterInformationResponseRepository {
    override fun record(
        tenant: TenantId,
        response: RequesterInformationResponse,
    ): RequesterInformationResponseRecording {
        val created =
            jdbc
                .sql(
                    """
                    INSERT INTO requester_information_responses
                        (tenant_id, response_id, request_id, answer, responded_at)
                    VALUES (:tenant, :response, :request, :answer, :at)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("response", response.responseId.value)
                .param("request", response.requestId.value)
                .param("answer", response.answer.value)
                .param("at", response.respondedAt.atOffset(ZoneOffset.UTC))
                .update() == 1
        // A new READ COMMITTED statement observes the winner after either unique-key wait.
        // Intent comparison belongs to the authorized use case, not this historical storage boundary.
        val row =
            jdbc
                .sql(
                    """
                    SELECT s.response_id, s.request_id, s.answer, s.responded_at, s.recorded_at,
                        q.case_id, c.requester_actor_id
                    FROM requester_information_responses s
                    JOIN requester_information_requests q
                        ON q.tenant_id = s.tenant_id AND q.request_id = s.request_id
                    JOIN case_requesters c ON c.tenant_id = q.tenant_id AND c.case_id = q.case_id
                    WHERE s.tenant_id = :tenant AND (s.response_id = :response OR s.request_id = :request)
                    ORDER BY (s.response_id = :response) DESC
                    LIMIT 1
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("response", response.responseId.value)
                .param("request", response.requestId.value)
                .query(DataClassRowMapper(InformationResponseRow::class.java))
                .single()
        return RequesterInformationResponseRecording(row.toStored(), created)
    }
}

private data class InformationResponseRow(
    val responseId: UUID,
    val requestId: UUID,
    val answer: String,
    val respondedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val caseId: UUID,
    val requesterActorId: UUID,
) {
    fun toStored(): StoredRequesterInformationResponse =
        StoredRequesterInformationResponse(
            RequesterInformationResponse(
                RequesterInformationResponseId(responseId),
                RequesterInformationRequestId(requestId),
                RequesterInformationAnswer(answer),
                respondedAt.toInstant(),
            ),
            CaseId(caseId),
            HumanActorId(requesterActorId),
            recordedAt.toInstant(),
        )
}
