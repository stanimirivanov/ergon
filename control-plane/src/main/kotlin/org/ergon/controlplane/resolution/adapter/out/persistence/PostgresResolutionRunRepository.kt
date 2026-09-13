package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.CaseNotFoundException
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ResolutionRunAlreadyExistsException
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.ergon.resolution.domain.ResolutionRunStart
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/**
 * PostgreSQL adapter for immutable resolution-run start snapshots.
 *
 * [create] locks the case projection row before checking its evidence version.
 * Callers must invoke it within [TransactionRunner.required], otherwise the lock
 * cannot protect the subsequent insert from a concurrent case command.
 */
@Repository
class PostgresResolutionRunRepository(
    private val jdbcClient: JdbcClient,
) : ResolutionRunRepository {
    override fun create(
        tenantId: TenantId,
        run: ResolutionRunStart,
    ): StoredResolutionRunStart {
        val actualVersion = lockCaseVersion(tenantId, run.caseId)
        if (actualVersion != run.caseStreamVersion) {
            throw ConcurrentCaseModificationException(run.caseStreamVersion, actualVersion)
        }
        val recordedAt =
            jdbcClient
                .sql(
                    """
                    INSERT INTO resolution_runs (
                        tenant_id, run_id, case_id, case_stream_version,
                        contract_key, contract_revision, policy_revision,
                        step_id, capability, effective_risk, required_approval, initial_state
                    ) VALUES (
                        :tenantId, :runId, :caseId, :caseStreamVersion,
                        :contractKey, :contractRevision, :policyRevision,
                        :stepId, :capability, :effectiveRisk, :requiredApproval, :initialState
                    )
                    ON CONFLICT (tenant_id, case_id) DO NOTHING
                    RETURNING recorded_at
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("runId", run.id.value)
                .param("caseId", run.caseId.value)
                .param("caseStreamVersion", run.caseStreamVersion)
                .param("contractKey", run.contract.key.value)
                .param("contractRevision", run.contract.revision.value)
                .param("policyRevision", run.policyRevision.value)
                .param("stepId", run.stepId.value)
                .param("capability", run.capability.value)
                .param("effectiveRisk", run.effectiveRisk.name)
                .param("requiredApproval", run.requiredApproval.name)
                .param("initialState", run.initialState.name)
                .query(OffsetDateTime::class.java)
                .optional()
                .getOrNull()
                ?: throw ResolutionRunAlreadyExistsException(run.caseId.value)
        return StoredResolutionRunStart(run, recordedAt.toInstant())
    }

    override fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunStart? =
        jdbcClient
            .sql(
                """
                SELECT
                    run_id,
                    case_id,
                    case_stream_version,
                    contract_key,
                    contract_revision,
                    policy_revision,
                    step_id,
                    capability,
                    effective_risk,
                    required_approval,
                    initial_state,
                    recorded_at
                FROM resolution_runs
                WHERE tenant_id = :tenantId AND run_id = :runId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(ResolutionRunRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredRun()

    private fun lockCaseVersion(
        tenantId: TenantId,
        caseId: CaseId,
    ): Long =
        jdbcClient
            .sql(
                """
                SELECT stream_version
                FROM cases
                WHERE tenant_id = :tenantId AND case_id = :caseId
                FOR UPDATE
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("caseId", caseId.value)
            .query(Long::class.java)
            .optional()
            .getOrNull()
            ?: throw CaseNotFoundException(tenantId.value, caseId.value)

    private fun ResolutionRunRow.toStoredRun(): StoredResolutionRunStart =
        try {
            StoredResolutionRunStart(
                run =
                    ResolutionRunStart(
                        id = ResolutionRunId(runId),
                        caseId = CaseId(caseId),
                        caseStreamVersion = caseStreamVersion,
                        contract =
                            ResolutionContractIdentity(
                                ResolutionContractKey.of(contractKey),
                                ResolutionContractRevision.of(contractRevision),
                            ),
                        policyRevision = ResolutionPolicyRevision.of(policyRevision),
                        stepId = ResolutionStepId.of(stepId),
                        capability = CapabilityName.of(capability),
                        effectiveRisk = StepRisk.valueOf(effectiveRisk),
                        requiredApproval = ApprovalRequirement.valueOf(requiredApproval),
                        initialState = ResolutionRunInitialState.valueOf(initialState),
                    ),
                recordedAt = recordedAt.toInstant(),
            )
        } catch (exception: IllegalArgumentException) {
            // Invalid persisted values indicate schema drift or corruption, never a client error.
            throw IllegalStateException("stored resolution run start is invalid", exception)
        }
}

private data class ResolutionRunRow(
    val runId: UUID,
    val caseId: UUID,
    val caseStreamVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val effectiveRisk: String,
    val requiredApproval: String,
    val initialState: String,
    val recordedAt: OffsetDateTime,
)
