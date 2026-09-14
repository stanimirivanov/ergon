package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.resolution.application.ApprovalDecisionAlreadyExistsException
import org.ergon.controlplane.resolution.application.ApprovalDecisionRepository
import org.ergon.controlplane.resolution.application.StoredApprovalDecision
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalDecision
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalDecisionOutcome
import org.ergon.resolution.domain.ApprovalDecisionSnapshot
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for immutable, one-response-per-request approval decisions. */
@Repository
class PostgresApprovalDecisionRepository(
    private val jdbcClient: JdbcClient,
) : ApprovalDecisionRepository {
    override fun findIdByRequest(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): ApprovalDecisionId? =
        jdbcClient
            .sql(
                """
                SELECT approval_decision_id
                FROM resolution_approval_decisions
                WHERE tenant_id = :tenantId AND approval_request_id = :requestId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("requestId", requestId.value)
            .query(UUID::class.java)
            .optional()
            .getOrNull()
            ?.let(::ApprovalDecisionId)

    override fun lockForAuthorization(
        tenantId: TenantId,
        decisionId: ApprovalDecisionId,
    ): StoredApprovalDecision? =
        jdbcClient
            .sql(
                """
                SELECT
                    approval_decision_id,
                    approval_request_id,
                    run_id,
                    case_id,
                    actor_id,
                    evidence_id,
                    authority,
                    outcome,
                    decided_at,
                    recorded_at
                FROM resolution_approval_decisions
                WHERE tenant_id = :tenantId AND approval_decision_id = :decisionId
                FOR UPDATE
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("decisionId", decisionId.value)
            .query(DataClassRowMapper(ApprovalDecisionRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredDecision()

    override fun create(
        tenantId: TenantId,
        decision: ApprovalDecision,
    ): StoredApprovalDecision {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_approval_decisions (
                        tenant_id, approval_decision_id, approval_request_id, run_id,
                        case_id, actor_id, evidence_id, authority, outcome, decided_at
                    ) VALUES (
                        :tenantId, :decisionId, :requestId, :runId,
                        :caseId, :actorId, :evidenceId, :authority, :outcome, :decidedAt
                    )
                    ON CONFLICT (tenant_id, approval_request_id) DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("decisionId", decision.id.value)
                .param("requestId", decision.requestId.value)
                .param("runId", decision.runId.value)
                .param("caseId", decision.caseId.value)
                .param("actorId", decision.actorId.value)
                .param("evidenceId", decision.authorityEvidenceId.value)
                .param("authority", decision.authority.name)
                .param("outcome", decision.outcome.name)
                .param("decidedAt", decision.decidedAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: throw ApprovalDecisionAlreadyExistsException(
                    requireNotNull(findIdByRequest(tenantId, decision.requestId)).value,
                )
        return StoredApprovalDecision(decision, recordedAt.toInstant())
    }
}

private fun ApprovalDecisionRow.toStoredDecision(): StoredApprovalDecision =
    try {
        StoredApprovalDecision(
            decision =
                ApprovalDecision.rehydrate(
                    ApprovalDecisionSnapshot(
                        id = ApprovalDecisionId(approvalDecisionId),
                        requestId = ApprovalRequestId(approvalRequestId),
                        runId = ResolutionRunId(runId),
                        actorId = HumanActorId(actorId),
                        authorityEvidenceId = ApprovalAuthorityEvidenceId(evidenceId),
                        authority = ApprovalAuthority.valueOf(authority),
                        caseId = CaseId(caseId),
                        outcome = ApprovalDecisionOutcome.valueOf(outcome),
                        decidedAt = decidedAt.toInstant(),
                    ),
                ),
            recordedAt = recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        // Invalid persisted values indicate schema drift or corruption, never client input.
        throw IllegalStateException("stored approval decision is invalid", exception)
    }

private data class ApprovalDecisionRow(
    val approvalDecisionId: UUID,
    val approvalRequestId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val actorId: UUID,
    val evidenceId: UUID,
    val authority: String,
    val outcome: String,
    val decidedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
