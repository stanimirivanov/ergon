package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.ResolutionRunCancelledException
import org.ergon.controlplane.resolution.application.ResolutionRunNotFoundException
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Serializes legacy authorization with browser cancellation before any child
 * row lock. NO KEY UPDATE permits FK key-share checks without deadlocking
 * state-first runtime writers. Caller must retain one surrounding transaction.
 */
internal class PostgresRunWriteFence(
    private val jdbc: JdbcClient,
) {
    fun lock(
        tenantId: TenantId,
        runId: UUID,
    ) {
        val found =
            jdbc
                .sql("SELECT run_id FROM resolution_runs WHERE tenant_id = :tenant AND run_id = :run FOR NO KEY UPDATE")
                .param("tenant", tenantId.value)
                .param("run", runId)
                .query(UUID::class.java)
                .optional()
                .isPresent
        if (!found) throw ResolutionRunNotFoundException(runId)
        val cancelled =
            jdbc
                .sql(
                    """
                    SELECT state = 'CANCELLED' FROM resolution_run_states
                    WHERE tenant_id = :tenant AND run_id = :run
                    """.trimIndent(),
                ).param("tenant", tenantId.value)
                .param("run", runId)
                .query(Boolean::class.java)
                .single()
        if (cancelled) throw ResolutionRunCancelledException()
    }

    /** Immutable child lookup reveals scope only internally; the row is reread under its usual lock afterward. */
    fun lockSource(
        tenantId: TenantId,
        sourceSql: String,
        id: UUID,
    ) {
        jdbc
            .sql(sourceSql)
            .param("tenant", tenantId.value)
            .param("id", id)
            .query(UUID::class.java)
            .optional()
            .ifPresent { lock(tenantId, it) }
    }
}
