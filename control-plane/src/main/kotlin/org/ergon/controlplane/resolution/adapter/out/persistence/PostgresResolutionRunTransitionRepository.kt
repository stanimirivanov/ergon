package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultRecording
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredResolutionRunCapabilityResult
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionRunCapabilityResult
import org.ergon.resolution.domain.ResolutionRunCapabilityResultSnapshot
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL event append and current-state projection adapter for resolution runs. */
@Repository
class PostgresResolutionRunTransitionRepository(
    private val jdbcClient: JdbcClient,
) : ResolutionRunTransitionRepository {
    override fun lockState(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): ResolutionRunStateSnapshot? =
        jdbcClient
            .sql(
                """
                SELECT run_id, state, version, updated_at
                FROM resolution_run_states
                WHERE tenant_id = :tenantId AND run_id = :runId
                FOR UPDATE
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(ResolutionRunStateRow::class.java))
            .optional()
            .getOrNull()
            ?.toSnapshot()

    override fun findByReceipt(
        tenantId: TenantId,
        consumptionId: CapabilityAuthorizationConsumptionId,
    ): StoredResolutionRunCapabilityResult? =
        jdbcClient
            .sql(
                """
                SELECT
                    event_id, run_id, sequence, event_type, from_state, to_state,
                    authorization_consumption_id, receipt_outcome, occurred_at, recorded_at
                FROM resolution_run_events
                WHERE tenant_id = :tenantId AND authorization_consumption_id = :consumptionId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("consumptionId", consumptionId.value)
            .query(DataClassRowMapper(ResolutionRunCapabilityResultRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredEvent()

    override fun append(
        tenantId: TenantId,
        event: ResolutionRunCapabilityResult,
    ): ResolutionRunCapabilityResultRecording {
        val recordedAt = insertEvent(tenantId, event)
        val updated =
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_run_states
                    SET state = :state, version = :version, updated_at = :updatedAt
                    WHERE tenant_id = :tenantId
                        AND run_id = :runId
                        AND version = :expectedVersion
                    """.trimIndent(),
                ).param("state", event.toState.name)
                .param("version", event.sequence)
                .param("updatedAt", event.occurredAt.atOffset(ZoneOffset.UTC))
                .param("tenantId", tenantId.value)
                .param("runId", event.runId.value)
                .param("expectedVersion", event.sequence - 1)
                .update()
        check(updated == 1) { "resolution run state changed before event projection" }
        return ResolutionRunCapabilityResultRecording(
            storedEvent = StoredResolutionRunCapabilityResult(event, recordedAt.toInstant()),
            currentState =
                ResolutionRunStateSnapshot(
                    event.runId,
                    event.toState,
                    event.sequence,
                    event.occurredAt,
                ),
            created = true,
        )
    }

    private fun insertEvent(
        tenantId: TenantId,
        event: ResolutionRunCapabilityResult,
    ): OffsetDateTime =
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_run_events (
                    tenant_id, run_id, sequence, event_id, event_type,
                    from_state, to_state, authorization_consumption_id,
                    receipt_outcome, occurred_at
                ) VALUES (
                    :tenantId, :runId, :sequence, :eventId, :eventType,
                    :fromState, :toState, :consumptionId,
                    :receiptOutcome, :occurredAt
                )
                RETURNING recorded_at
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", event.runId.value)
            .param("sequence", event.sequence)
            .param("eventId", event.id.value)
            .param("eventType", event.type.name)
            .param("fromState", event.fromState.name)
            .param("toState", event.toState.name)
            .param("consumptionId", event.authorizationConsumptionId.value)
            .param("receiptOutcome", event.receiptOutcome.name)
            .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .query(OffsetDateTime::class.java)
            .single()
}

private fun ResolutionRunStateRow.toSnapshot(): ResolutionRunStateSnapshot =
    try {
        ResolutionRunStateSnapshot(
            ResolutionRunId(runId),
            ResolutionRunState.valueOf(state),
            version,
            updatedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored resolution run state is invalid", exception)
    }

private fun ResolutionRunCapabilityResultRow.toStoredEvent(): StoredResolutionRunCapabilityResult =
    try {
        StoredResolutionRunCapabilityResult(
            event =
                ResolutionRunCapabilityResult.rehydrate(
                    ResolutionRunCapabilityResultSnapshot(
                        id = ResolutionRunEventId(eventId),
                        runId = ResolutionRunId(runId),
                        sequence = sequence,
                        type = ResolutionRunEventType.valueOf(eventType),
                        fromState = ResolutionRunState.valueOf(fromState),
                        toState = ResolutionRunState.valueOf(toState),
                        authorizationConsumptionId =
                            CapabilityAuthorizationConsumptionId(authorizationConsumptionId),
                        receiptOutcome = CapabilityInvocationOutcome.valueOf(receiptOutcome),
                        occurredAt = occurredAt.toInstant(),
                    ),
                ),
            recordedAt = recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored resolution run event is invalid", exception)
    }

private data class ResolutionRunStateRow(
    val runId: UUID,
    val state: String,
    val version: Long,
    val updatedAt: OffsetDateTime,
)

private data class ResolutionRunCapabilityResultRow(
    val eventId: UUID,
    val runId: UUID,
    val sequence: Long,
    val eventType: String,
    val fromState: String,
    val toState: String,
    val authorizationConsumptionId: UUID,
    val receiptOutcome: String,
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
