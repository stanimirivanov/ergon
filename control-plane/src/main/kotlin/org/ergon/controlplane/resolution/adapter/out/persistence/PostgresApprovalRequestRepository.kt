package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ActiveApprovalRequestExistsException
import org.ergon.controlplane.resolution.application.ApprovalRequestRepository
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.ergon.controlplane.resolution.application.StoredApprovalRequest
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/**
 * PostgreSQL adapter for immutable, expiring approval requests.
 *
 * [create] locks the immutable run row before inspecting request history, so
 * concurrent callers cannot both observe that no active request exists. The
 * caller must retain this lock through [TransactionRunner.required].
 */
@Repository
class PostgresApprovalRequestRepository(
    private val jdbcClient: JdbcClient,
) : ApprovalRequestRepository {
    override fun create(
        tenantId: TenantId,
        request: ApprovalRequest,
    ): StoredApprovalRequest {
        lockRun(tenantId, request.runId)
        findLatest(tenantId, request.runId)?.let { active ->
            val expiresAt = active.expiresAt.toInstant()
            if (expiresAt.isAfter(request.requestedAt)) {
                throw ActiveApprovalRequestExistsException(active.approvalRequestId, expiresAt)
            }
        }
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_approval_requests (
                        tenant_id, approval_request_id, run_id, step_id,
                        required_authority, requested_at, expires_at
                    ) VALUES (
                        :tenantId, :requestId, :runId, :stepId,
                        :requiredAuthority, :requestedAt, :expiresAt
                    )
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("requestId", request.id.value)
                .param("runId", request.runId.value)
                .param("stepId", request.stepId.value)
                .param("requiredAuthority", request.authority.name)
                .param("requestedAt", request.requestedAt.atOffset(ZoneOffset.UTC))
                .param("expiresAt", request.expiresAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .single()
        return StoredApprovalRequest(request, recordedAt.toInstant())
    }

    override fun find(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): StoredApprovalRequest? = find(tenantId, requestId, forUpdate = false)

    override fun lockForDecision(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): StoredApprovalRequest? = find(tenantId, requestId, forUpdate = true)

    private fun find(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
        forUpdate: Boolean,
    ): StoredApprovalRequest? {
        val lockClause = if (forUpdate) "FOR UPDATE" else ""
        return jdbcClient
            .sql(
                """
                SELECT
                    approval_request_id,
                    run_id,
                    step_id,
                    required_authority,
                    requested_at,
                    expires_at,
                    recorded_at
                FROM resolution_approval_requests
                WHERE tenant_id = :tenantId
                    AND approval_request_id = :requestId
                $lockClause
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("requestId", requestId.value)
            .query(DataClassRowMapper(ApprovalRequestRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredRequest()
    }

    private fun lockRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ) {
        val found =
            jdbcClient
                .sql(
                    """
                    SELECT run_id
                    FROM resolution_runs
                    WHERE tenant_id = :tenantId AND run_id = :runId
                    FOR UPDATE
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("runId", runId.value)
                .query(UUID::class.java)
                .optional()
                .isPresent
        if (!found) {
            throw ResolutionRunNotFoundException(runId.value)
        }
    }

    private fun findLatest(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): LatestApprovalRequestRow? =
        jdbcClient
            .sql(
                """
                SELECT approval_request_id, expires_at
                FROM resolution_approval_requests
                WHERE tenant_id = :tenantId AND run_id = :runId
                ORDER BY recorded_at DESC, approval_request_id DESC
                LIMIT 1
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(LatestApprovalRequestRow::class.java))
            .optional()
            .getOrNull()

    private fun ApprovalRequestRow.toStoredRequest(): StoredApprovalRequest =
        try {
            StoredApprovalRequest(
                request =
                    ApprovalRequest(
                        id = ApprovalRequestId(approvalRequestId),
                        runId = ResolutionRunId(runId),
                        stepId = ResolutionStepId.of(stepId),
                        authority = ApprovalAuthority.valueOf(requiredAuthority),
                        requestedAt = requestedAt.toInstant(),
                        expiresAt = expiresAt.toInstant(),
                    ),
                recordedAt = recordedAt.toInstant(),
            )
        } catch (exception: IllegalArgumentException) {
            // Invalid durable values are an operator-visible corruption failure, not client input.
            throw IllegalStateException("stored approval request is invalid", exception)
        }
}

private data class LatestApprovalRequestRow(
    val approvalRequestId: UUID,
    val expiresAt: OffsetDateTime,
)

private data class ApprovalRequestRow(
    val approvalRequestId: UUID,
    val runId: UUID,
    val stepId: String,
    val requiredAuthority: String,
    val requestedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
