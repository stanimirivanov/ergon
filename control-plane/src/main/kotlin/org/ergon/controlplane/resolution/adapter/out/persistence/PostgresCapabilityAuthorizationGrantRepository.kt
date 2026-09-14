package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantAlreadyExistsException
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityAuthorizationGrant
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrant
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for immutable, one-grant-per-decision capability authorizations. */
@Repository
class PostgresCapabilityAuthorizationGrantRepository(
    private val jdbcClient: JdbcClient,
) : CapabilityAuthorizationGrantRepository {
    override fun findIdByDecision(
        tenantId: TenantId,
        decisionId: ApprovalDecisionId,
    ): CapabilityAuthorizationGrantId? =
        jdbcClient
            .sql(
                """
                SELECT authorization_grant_id
                FROM capability_authorization_grants
                WHERE tenant_id = :tenantId AND approval_decision_id = :decisionId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("decisionId", decisionId.value)
            .query(UUID::class.java)
            .optional()
            .getOrNull()
            ?.let(::CapabilityAuthorizationGrantId)

    override fun create(
        tenantId: TenantId,
        grant: CapabilityAuthorizationGrant,
    ): StoredCapabilityAuthorizationGrant {
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO capability_authorization_grants (
                        tenant_id, authorization_grant_id, approval_decision_id,
                        approval_request_id, run_id, case_id, policy_revision,
                        step_id, capability, decision_outcome, authorized_at, expires_at
                    ) VALUES (
                        :tenantId, :grantId, :decisionId,
                        :requestId, :runId, :caseId, :policyRevision,
                        :stepId, :capability, 'APPROVED', :authorizedAt, :expiresAt
                    )
                    ON CONFLICT (tenant_id, approval_decision_id) DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("grantId", grant.id.value)
                .param("decisionId", grant.approvalDecisionId.value)
                .param("requestId", grant.approvalRequestId.value)
                .param("runId", grant.runId.value)
                .param("caseId", grant.caseId.value)
                .param("policyRevision", grant.policyRevision.value)
                .param("stepId", grant.stepId.value)
                .param("capability", grant.capability.value)
                .param("authorizedAt", grant.authorizedAt.atOffset(ZoneOffset.UTC))
                .param("expiresAt", grant.expiresAt.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: throw CapabilityAuthorizationGrantAlreadyExistsException(
                    requireNotNull(findIdByDecision(tenantId, grant.approvalDecisionId)).value,
                )
        return StoredCapabilityAuthorizationGrant(grant, recordedAt.toInstant())
    }
}
