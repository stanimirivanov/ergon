package org.ergon.controlplane.followup.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.followup.application.RequesterInformationRequest
import org.ergon.controlplane.followup.application.RequesterInformationRequestRecording
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.StoredRequesterInformationRequest
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.followup.domain.RequesterInformationQuestion
import org.ergon.followup.domain.RequesterInformationRequestId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Uses the caller's transaction; unique request identity also serializes collisions across distinct work locks. */
@Repository
class PostgresRequesterInformationRequestRepository(
    private val jdbc: JdbcClient,
) : RequesterInformationRequestRepository {
    override fun record(
        tenant: TenantId,
        request: RequesterInformationRequest,
    ): RequesterInformationRequestRecording {
        val recordedAt =
            jdbc
                .sql(
                    """
                    INSERT INTO requester_information_requests
                        (tenant_id, request_id, work_item_id, case_id, ownership_revision, question, requested_at)
                    VALUES (:tenant, :id, :work, :case, :revision, :question, :at)
                    ON CONFLICT (tenant_id, request_id) DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("id", request.requestId.value)
                .param("work", request.workItemId.value)
                .param("case", request.caseId.value)
                .param("revision", request.ownershipRevision.value)
                .param("question", request.question.value)
                .param("at", request.requestedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
        // A separate READ COMMITTED statement observes the winner after ON CONFLICT waited.
        // Never merge a loser's text, attribution or timestamps into the durable winner.
        // Both paths use database precision and historical attribution, not caller copies.
        return RequesterInformationRequestRecording(loadWinner(tenant, request.requestId), recordedAt != null)
    }

    private fun loadWinner(
        tenant: TenantId,
        requestId: RequesterInformationRequestId,
    ): StoredRequesterInformationRequest {
        val row =
            jdbc
                .sql(
                    """
                    SELECT r.request_id, r.work_item_id, r.case_id, r.ownership_revision, r.question,
                        r.requested_at, r.recorded_at, o.claim_id, o.resolver_actor_id
                    FROM requester_information_requests r
                    JOIN human_follow_up_ownership_events o
                        ON o.tenant_id = r.tenant_id AND o.work_item_id = r.work_item_id
                        AND o.ownership_revision = r.ownership_revision
                    WHERE r.tenant_id = :tenant AND r.request_id = :id
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("id", requestId.value)
                .query(DataClassRowMapper(InformationRequestRow::class.java))
                .single()
        return row.toStored()
    }
}

private data class InformationRequestRow(
    val requestId: UUID,
    val workItemId: UUID,
    val caseId: UUID,
    val ownershipRevision: Long,
    val question: String,
    val requestedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val claimId: UUID,
    val resolverActorId: UUID,
) {
    fun toStored(): StoredRequesterInformationRequest =
        StoredRequesterInformationRequest(
            RequesterInformationRequest(
                RequesterInformationRequestId(requestId),
                HumanFollowUpWorkItemId(workItemId),
                CaseId(caseId),
                HumanFollowUpClaimId(claimId),
                HumanFollowUpOwnershipRevision(ownershipRevision),
                HumanActorId(resolverActorId),
                RequesterInformationQuestion(question),
                requestedAt.toInstant(),
            ),
            recordedAt.toInstant(),
        )
}
