package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.resolution.application.CapabilityInvocationExecution
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationReceiptSnapshot
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL adapter for immutable terminal capability-invocation receipts. */
@Repository
class PostgresCapabilityInvocationReceiptRepository(
    private val jdbcClient: JdbcClient,
) : CapabilityInvocationReceiptRepository {
    override fun find(
        tenantId: TenantId,
        consumptionId: CapabilityAuthorizationConsumptionId,
    ): StoredCapabilityInvocationReceipt? =
        jdbcClient
            .sql(
                """
                SELECT
                    authorization_consumption_id, authorization_grant_id, run_id, case_id,
                    policy_revision, step_id, capability, connector, idempotency_key,
                    outcome, provider_operation_reference, consumption_consumed_at,
                    completed_at, recorded_at
                FROM capability_invocation_receipts
                WHERE tenant_id = :tenantId AND authorization_consumption_id = :consumptionId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("consumptionId", consumptionId.value)
            .query(DataClassRowMapper(CapabilityInvocationReceiptRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredReceipt()

    override fun createOrFind(
        tenantId: TenantId,
        receipt: CapabilityInvocationReceipt,
    ): CapabilityInvocationExecution {
        val recordedAt = insert(tenantId, receipt)
        if (recordedAt != null) {
            return CapabilityInvocationExecution(
                StoredCapabilityInvocationReceipt(receipt, recordedAt.toInstant()),
                created = true,
            )
        }
        val existing =
            checkNotNull(find(tenantId, receipt.authorizationConsumptionId)) {
                "conflicting capability invocation receipt was not visible"
            }
        return CapabilityInvocationExecution(existing, created = false)
    }

    private fun insert(
        tenantId: TenantId,
        receipt: CapabilityInvocationReceipt,
    ): OffsetDateTime? =
        jdbcClient
            .sql(
                """
                INSERT INTO capability_invocation_receipts (
                    tenant_id, authorization_consumption_id, authorization_grant_id,
                    run_id, case_id, policy_revision, step_id, capability, connector,
                    idempotency_key, outcome, provider_operation_reference,
                    consumption_consumed_at, completed_at
                ) VALUES (
                    :tenantId, :consumptionId, :grantId,
                    :runId, :caseId, :policyRevision, :stepId, :capability, :connector,
                    :idempotencyKey, :outcome, :providerOperationReference,
                    :consumptionConsumedAt, :completedAt
                )
                ON CONFLICT (tenant_id, authorization_consumption_id) DO NOTHING
                RETURNING recorded_at
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("consumptionId", receipt.authorizationConsumptionId.value)
            .param("grantId", receipt.authorizationGrantId.value)
            .param("runId", receipt.runId.value)
            .param("caseId", receipt.caseId.value)
            .param("policyRevision", receipt.policyRevision.value)
            .param("stepId", receipt.stepId.value)
            .param("capability", receipt.capability.value)
            .param("connector", receipt.connector.value)
            .param("idempotencyKey", receipt.idempotencyKey)
            .param("outcome", receipt.outcome.name)
            .param("providerOperationReference", receipt.providerOperationReference.value)
            .param("consumptionConsumedAt", receipt.consumptionConsumedAt.atOffset(ZoneOffset.UTC))
            .param("completedAt", receipt.completedAt.atOffset(ZoneOffset.UTC))
            .query(OffsetDateTime::class.java)
            .optional()
            .getOrNull()
}

private fun CapabilityInvocationReceiptRow.toStoredReceipt(): StoredCapabilityInvocationReceipt =
    try {
        StoredCapabilityInvocationReceipt(
            receipt =
                CapabilityInvocationReceipt.rehydrate(
                    CapabilityInvocationReceiptSnapshot(
                        authorizationConsumptionId =
                            CapabilityAuthorizationConsumptionId(authorizationConsumptionId),
                        authorizationGrantId = CapabilityAuthorizationGrantId(authorizationGrantId),
                        runId = ResolutionRunId(runId),
                        caseId = CaseId(caseId),
                        policyRevision = ResolutionPolicyRevision.of(policyRevision),
                        stepId = ResolutionStepId.of(stepId),
                        capability = CapabilityName.of(capability),
                        connector = ConnectorName.of(connector),
                        idempotencyKey = idempotencyKey,
                        outcome = CapabilityInvocationOutcome.valueOf(outcome),
                        providerOperationReference = ProviderOperationReference.of(providerOperationReference),
                        consumptionConsumedAt = consumptionConsumedAt.toInstant(),
                        completedAt = completedAt.toInstant(),
                    ),
                ),
            recordedAt = recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored capability invocation receipt is invalid", exception)
    }

private data class CapabilityInvocationReceiptRow(
    val authorizationConsumptionId: UUID,
    val authorizationGrantId: UUID,
    val runId: UUID,
    val caseId: UUID,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val connector: String,
    val idempotencyKey: UUID,
    val outcome: String,
    val providerOperationReference: String,
    val consumptionConsumedAt: OffsetDateTime,
    val completedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
