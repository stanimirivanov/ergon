package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.MAX_EXECUTION_ADMISSIONS
import org.ergon.controlplane.resolution.application.RecordedExecutionAdmission
import org.ergon.controlplane.resolution.application.RecordedExecutionAdmissions
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionKind
import org.ergon.controlplane.resolution.application.RunExecutionHistoryReader
import org.ergon.controlplane.resolution.application.RunSupervisionRevision
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** One statement snapshot distinguishes no browser command from a bounded, sanitized admission history. */
@Repository
class PostgresRunExecutionHistoryReader(
    private val jdbc: JdbcClient,
) : RunExecutionHistoryReader {
    override fun read(
        tenant: TenantId,
        run: ResolutionRunId,
    ): RecordedExecutionAdmissions? {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT CASE WHEN a.attempt_id = c.command_id THEN 'INITIAL' ELSE 'RECOVERY' END AS kind,
                        a.supervision_revision, a.admitted_at, a.recorded_at
                    FROM resolution_run_execution_commands c
                    LEFT JOIN resolution_run_execution_attempts a
                        ON a.tenant_id = c.tenant_id AND a.command_id = c.command_id AND a.run_id = c.run_id
                    WHERE c.tenant_id = :tenant AND c.run_id = :run
                    ORDER BY a.recorded_at, a.attempt_id
                    LIMIT :limit
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("run", run.value)
                .param("limit", MAX_EXECUTION_ADMISSIONS + 1)
                .query(DataClassRowMapper(AdmissionHistoryRow::class.java))
                .list()
        if (rows.isEmpty()) return null
        // A LEFT JOIN sentinel is a corrupt command without its atomic first admission,
        // not an empty history or permission to issue another command.
        check(rows.first().supervisionRevision != null) { "browser execution command has no admission" }
        return RecordedExecutionAdmissions(
            rows.take(MAX_EXECUTION_ADMISSIONS).map {
                RecordedExecutionAdmission(
                    RunExecutionAdmissionKind.valueOf(it.kind),
                    RunSupervisionRevision(checkNotNull(it.supervisionRevision)),
                    checkNotNull(it.admittedAt).toInstant(),
                    checkNotNull(it.recordedAt).toInstant(),
                )
            },
            truncated = rows.size > MAX_EXECUTION_ADMISSIONS,
        )
    }
}

private data class AdmissionHistoryRow(
    val kind: String,
    val supervisionRevision: Long?,
    val admittedAt: OffsetDateTime?,
    val recordedAt: OffsetDateTime?,
)
