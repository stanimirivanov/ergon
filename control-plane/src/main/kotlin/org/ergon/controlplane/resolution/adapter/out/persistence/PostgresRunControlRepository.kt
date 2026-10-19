package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.RunControlAction
import org.ergon.controlplane.resolution.application.RunControlAdmission
import org.ergon.controlplane.resolution.application.RunControlCommand
import org.ergon.controlplane.resolution.application.RunControlCommandKey
import org.ergon.controlplane.resolution.application.RunControlFence
import org.ergon.controlplane.resolution.application.RunControlRepository
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.controlplane.resolution.application.StoredRunControl
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** Explicit SQL lifecycle/audit adapter. The application retains the run lock and transaction ownership. */
@Repository
class PostgresRunControlRepository(
    private val jdbc: JdbcClient,
) : RunControlRepository {
    override fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "run-control:${tenant.value}:$commandId")
            .query { _, _ -> Unit }
            .single()
    }

    override fun findCommand(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunControl? =
        jdbc
            .sql("SELECT * FROM resolution_run_control_events WHERE tenant_id = :tenant AND command_id = :command")
            .param("tenant", tenant.value)
            .param("command", commandId)
            .query(DataClassRowMapper(ControlRow::class.java))
            .optional()
            .getOrNull()
            ?.stored()

    override fun current(
        tenant: TenantId,
        run: ResolutionRunId,
    ): StoredRunControl? =
        jdbc
            .sql(
                """
                SELECT event.* FROM resolution_run_control_events event
                JOIN resolution_run_control_heads head USING (tenant_id, run_id, sequence)
                WHERE event.tenant_id = :tenant AND event.run_id = :run
                """.trimIndent(),
            ).param("tenant", tenant.value)
            .param("run", run.value)
            .query(DataClassRowMapper(ControlRow::class.java))
            .optional()
            .getOrNull()
            ?.stored()

    override fun append(control: StoredRunControl): StoredRunControl {
        val command = control.command
        val recorded =
            jdbc
                .sql(
                    """
                    INSERT INTO resolution_run_control_events (tenant_id, run_id, sequence, command_id, action,
                        actor_id, supervision_revision, expected_lease_id, expected_fence, lease_id, fence,
                        holder_id, changed_at, expires_at, supervised_at)
                    VALUES (:tenant, :run, :sequence, :command, :action, :actor, :epoch, :expectedLease,
                        :expectedFence, :lease, :fence, :holder, :at, :expiry, :supervised) RETURNING recorded_at
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "tenant" to command.tenantId,
                        "run" to command.runId,
                        "sequence" to control.sequence,
                        "command" to command.commandId,
                        "action" to command.action.name,
                        "actor" to command.actorId,
                        "epoch" to command.supervisionRevision.value,
                        "expectedLease" to command.expectedFence?.leaseId,
                        "expectedFence" to command.expectedFence?.revision,
                        "lease" to control.fence.leaseId,
                        "fence" to control.fence.revision,
                        "holder" to control.holderId,
                        "at" to control.changedAt.atOffset(ZoneOffset.UTC),
                        "expiry" to control.expiresAt.atOffset(ZoneOffset.UTC),
                        "supervised" to control.supervisedAt.atOffset(ZoneOffset.UTC),
                    ),
                ).query(OffsetDateTime::class.java)
                .single()
                .toInstant()
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_control_heads (tenant_id, run_id, sequence) VALUES (:tenant, :run, :sequence)
                ON CONFLICT (tenant_id, run_id) DO UPDATE SET sequence = EXCLUDED.sequence
                """.trimIndent(),
            ).param("tenant", command.tenantId)
            .param("run", command.runId)
            .param("sequence", control.sequence)
            .update()
        return control.copy(recordedAt = recorded)
    }

    override fun admission(key: RunControlCommandKey): RunControlAdmission? =
        jdbc
            .sql(
                """
                SELECT event.*, admission.admitted_at FROM resolution_run_control_admissions admission
                JOIN resolution_run_control_events event ON event.tenant_id = admission.tenant_id
                    AND event.run_id = admission.run_id AND event.sequence = admission.lease_sequence
                WHERE admission.tenant_id = :tenant AND admission.run_id = :run
                    AND admission.kind = :kind AND admission.command_id = :command
                """.trimIndent(),
            ).params(key.parameters())
            .query { row, _ ->
                RunControlAdmission(
                    key,
                    checkNotNull(DataClassRowMapper(ControlRow::class.java).mapRow(row, 0)).stored(),
                    row.getObject("admitted_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .getOrNull()

    override fun recordAdmission(admission: RunControlAdmission) {
        val lease = admission.control
        jdbc
            .sql(
                """
                INSERT INTO resolution_run_control_admissions (tenant_id, run_id, kind, command_id, lease_sequence,
                    lease_id, fence, supervision_revision, actor_id, holder_id, admitted_at)
                VALUES (:tenant, :run, :kind, :command, :sequence, :lease, :fence, :epoch, :actor, :holder, :at)
                """.trimIndent(),
            ).params(
                admission.key.parameters() +
                    mapOf(
                        "sequence" to lease.sequence,
                        "lease" to lease.fence.leaseId,
                        "fence" to lease.fence.revision,
                        "epoch" to lease.command.supervisionRevision.value,
                        "actor" to lease.command.actorId,
                        "holder" to lease.holderId,
                        "at" to admission.admittedAt.atOffset(ZoneOffset.UTC),
                    ),
            ).update()
    }
}

private fun RunControlCommandKey.parameters(): Map<String, Any> =
    mapOf("tenant" to tenantId, "run" to runId, "kind" to kind.name, "command" to commandId)

/** Column labels intentionally match constructor names; timestamps retain PostgreSQL microsecond precision. */
private data class ControlRow(
    val tenantId: UUID,
    val runId: UUID,
    val sequence: Long,
    val commandId: UUID,
    val action: String,
    val actorId: UUID,
    val supervisionRevision: Long,
    val expectedLeaseId: UUID?,
    val expectedFence: Long?,
    val leaseId: UUID,
    val fence: Long,
    val holderId: UUID,
    val changedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val supervisedAt: OffsetDateTime,
) {
    fun stored(): StoredRunControl =
        StoredRunControl(
            RunControlCommand(
                tenantId,
                runId,
                actorId,
                commandId,
                RunSupervisionRevision(supervisionRevision),
                RunControlAction.valueOf(action),
                expectedLeaseId?.let { RunControlFence(it, checkNotNull(expectedFence)) },
            ),
            sequence,
            RunControlFence(leaseId, fence),
            holderId,
            changedAt.toInstant(),
            expiresAt.toInstant(),
            recordedAt.toInstant(),
            supervisedAt.toInstant(),
        )
}
