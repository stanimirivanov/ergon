package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.controlplane.resolution.application.CapabilityInvocationCallOutcome
import org.ergon.controlplane.resolution.application.CapabilityInvocationCompletion
import org.ergon.controlplane.resolution.application.MAX_INVOCATION_ATTEMPTS
import org.ergon.controlplane.resolution.application.RecordedInvocationAttempt
import org.ergon.controlplane.resolution.application.RecordedInvocationAttempts
import org.ergon.controlplane.resolution.application.RecordedInvocationCompletion
import org.ergon.controlplane.resolution.application.RunInvocationHistoryReader
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityInvocationTiming
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ResolutionRunId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** One statement snapshot retains incomplete calls and each concurrent caller's own measurement. */
@Repository
class PostgresRunInvocationHistoryReader(
    private val jdbc: JdbcClient,
) : RunInvocationHistoryReader {
    override fun read(
        tenant: TenantId,
        run: ResolutionRunId,
    ): RecordedInvocationAttempts {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT c.step_id, c.capability, c.connector, p.prepared_at, p.recorded_at,
                        r.outcome, r.invocation_started_at, r.completed_at, r.elapsed_nanoseconds,
                        r.recorded_at AS completion_recorded_at
                    FROM capability_authorization_consumptions c
                    JOIN capability_invocation_preparations p
                        ON p.tenant_id = c.tenant_id AND p.authorization_consumption_id = c.authorization_consumption_id
                    LEFT JOIN capability_invocation_completions r
                        ON r.tenant_id = p.tenant_id AND r.invocation_attempt_id = p.invocation_attempt_id
                    WHERE c.tenant_id = :tenant AND c.run_id = :run
                    ORDER BY p.recorded_at, p.invocation_attempt_id
                    LIMIT :limit
                    """.trimIndent(),
                ).param("tenant", tenant.value)
                .param("run", run.value)
                .param("limit", MAX_INVOCATION_ATTEMPTS + 1)
                .query(DataClassRowMapper(InvocationHistoryRow::class.java))
                .list()
        return RecordedInvocationAttempts(
            rows.take(MAX_INVOCATION_ATTEMPTS).map(InvocationHistoryRow::toRecorded),
            rows.size > MAX_INVOCATION_ATTEMPTS,
        )
    }
}

private data class InvocationHistoryRow(
    val stepId: String,
    val capability: String,
    val connector: String,
    val preparedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
    val outcome: String?,
    val invocationStartedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
    val elapsedNanoseconds: Long?,
    val completionRecordedAt: OffsetDateTime?,
) {
    fun toRecorded(): RecordedInvocationAttempt =
        try {
            RecordedInvocationAttempt(
                ResolutionStepId.of(stepId),
                CapabilityName.of(capability),
                ConnectorName.of(connector),
                preparedAt.toInstant(),
                recordedAt.toInstant(),
                outcome?.let {
                    RecordedInvocationCompletion(
                        CapabilityInvocationCompletion(
                            CapabilityInvocationCallOutcome.valueOf(it),
                            checkNotNull(completedAt).toInstant(),
                            CapabilityInvocationTiming(
                                checkNotNull(invocationStartedAt).toInstant(),
                                checkNotNull(elapsedNanoseconds),
                            ),
                        ),
                        checkNotNull(completionRecordedAt).toInstant(),
                    )
                },
            )
        } catch (invalid: IllegalArgumentException) {
            // Persisted invariants are service unavailability, not malformed GET input.
            throw IllegalStateException("recorded gateway history is inconsistent", invalid)
        }
}
