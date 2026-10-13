package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.RecordedRunTransition
import org.ergon.controlplane.resolution.application.RecordedRunTransitionReader
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Reads only Console transition metadata; event payloads and append transactions stay with their writers. */
@Repository
class PostgresRecordedRunTransitionReader(
    private val jdbcClient: JdbcClient,
) : RecordedRunTransitionReader {
    override fun listRecordedTransitions(
        tenantId: TenantId,
        runId: ResolutionRunId,
        throughSequence: Long,
    ): List<RecordedRunTransition> {
        require(throughSequence in 0..2) { "transition prefix must be in 0..2" }
        return jdbcClient
            .sql(
                """
                SELECT run_id, sequence, event_type, from_state, to_state, occurred_at, recorded_at
                FROM resolution_run_events
                WHERE tenant_id = :tenantId AND run_id = :runId AND sequence <= :throughSequence
                ORDER BY sequence ASC
                LIMIT 3
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .param("throughSequence", throughSequence)
            .query(DataClassRowMapper(RecordedRunTransitionRow::class.java))
            .list()
            .map { row ->
                try {
                    RecordedRunTransition(
                        ResolutionRunId(row.runId),
                        row.sequence,
                        ResolutionRunEventType.valueOf(row.eventType),
                        ResolutionRunState.valueOf(row.fromState),
                        ResolutionRunState.valueOf(row.toState),
                        row.occurredAt.toInstant(),
                        row.recordedAt.toInstant(),
                    )
                } catch (exception: IllegalArgumentException) {
                    throw IllegalStateException("stored run transition metadata is invalid", exception)
                }
            }
    }
}

private data class RecordedRunTransitionRow(
    val runId: UUID,
    val sequence: Long,
    val eventType: String,
    val fromState: String,
    val toState: String,
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
