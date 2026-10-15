package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.CurrentRunSupervision
import org.ergon.controlplane.resolution.application.FinalizeRunProofCommand
import org.ergon.controlplane.resolution.application.RunProofCommandRepository
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredRunProofCommand
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Tenant-scoped immutable proof receipts; caller owns the transaction and run lock. */
@Repository
class PostgresRunProofCommandRepository(
    private val jdbc: JdbcClient,
) : RunProofCommandRepository {
    override fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-proof:${tenant.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun findByCommandId(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunProofCommand? =
        jdbc
            .sql(
                """
                SELECT c.run_id, c.actor_id, c.supervision_revision, c.expected_run_version,
                    c.case_stream_version, c.fact_id, c.observation_id, p.accepted_at, p.recorded_at
                FROM resolution_run_proof_commands c
                JOIN resolution_outcome_proof_acceptances p
                    ON p.tenant_id = c.tenant_id AND p.run_id = c.run_id
                WHERE c.tenant_id = :tenant AND c.command_id = :command
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("command", commandId)
            .query(DataClassRowMapper(ProofCommandRow::class.java))
            .optional()
            .getOrNull()
            ?.let { row ->
                StoredRunProofCommand(
                    FinalizeRunProofCommand(
                        tenantId = tenant.value,
                        runId = row.runId,
                        actorId = row.actorId,
                        commandId = commandId,
                        expectedSupervisionRevision = RunSupervisionRevision(row.supervisionRevision),
                        expectedRunVersion = row.expectedRunVersion,
                        expectedCaseVersion = row.caseStreamVersion,
                        factId = row.factId,
                        observationId = row.observationId,
                    ),
                    row.acceptedAt.toInstant(),
                    row.recordedAt.toInstant(),
                )
            }

    override fun record(
        command: FinalizeRunProofCommand,
        supervision: CurrentRunSupervision,
        authorityEvidenceId: ApprovalAuthorityEvidenceId,
    ): StoredRunProofCommand {
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_proof_commands (
                    tenant_id, command_id, run_id, actor_id, supervision_revision, supervised_at,
                    authority_evidence_id, expected_run_version, case_stream_version, fact_id, observation_id
                ) VALUES (:tenant, :command, :run, :actor, :revision, :supervisedAt,
                    :evidence, :runVersion, :caseVersion, :fact, :observation)
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("command", command.commandId)
            .param("run", command.runId)
            .param("actor", command.actorId)
            .param("revision", command.expectedSupervisionRevision.value)
            .param("supervisedAt", supervision.supervisedAt.atOffset(ZoneOffset.UTC))
            .param("evidence", authorityEvidenceId.value)
            .param("runVersion", command.expectedRunVersion)
            .param("caseVersion", command.expectedCaseVersion)
            .param("fact", command.factId)
            .param("observation", command.observationId)
            .update()
        return checkNotNull(findByCommandId(TenantId(command.tenantId), command.commandId))
    }
}

private data class ProofCommandRow(
    val runId: UUID,
    val actorId: UUID,
    val supervisionRevision: Long,
    val expectedRunVersion: Long,
    val caseStreamVersion: Long,
    val factId: UUID,
    val observationId: UUID,
    val acceptedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
