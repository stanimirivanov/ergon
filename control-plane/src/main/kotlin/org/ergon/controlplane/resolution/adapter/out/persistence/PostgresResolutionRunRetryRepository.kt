package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.ResolutionRunRetryRecording
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.StoredResolutionRunRetry
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunRetryAuthorization
import org.ergon.resolution.domain.ResolutionRunRetrySnapshot
import org.ergon.resolution.domain.ResolutionRunRetryStarted
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/** PostgreSQL append and replay adapter for explicitly linked retry attempts. */
@Repository
class PostgresResolutionRunRetryRepository(
    private val jdbcClient: JdbcClient,
) : ResolutionRunRetryRepository {
    override fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunRetry? =
        jdbcClient
            .sql(
                """
                SELECT
                    event_id, run_id, sequence, from_state, to_state,
                    occurred_at, replacement_run_id, retry_actor_id,
                    retry_authority_evidence_id, recorded_at
                FROM resolution_run_events
                WHERE tenant_id = :tenantId AND run_id = :runId
                    AND event_type = 'RETRY_STARTED'
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(ResolutionRunRetryRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredRetry()

    override fun append(
        tenantId: TenantId,
        event: ResolutionRunRetryStarted,
    ): ResolutionRunRetryRecording {
        val recordedAt = insertEvent(tenantId, event)
        val state = updateState(tenantId, event)
        return ResolutionRunRetryRecording(
            StoredResolutionRunRetry(event, recordedAt.toInstant()),
            state,
            created = true,
        )
    }

    private fun insertEvent(
        tenantId: TenantId,
        event: ResolutionRunRetryStarted,
    ): OffsetDateTime =
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_run_events (
                    tenant_id, run_id, sequence, event_id, event_type,
                    from_state, to_state, replacement_run_id, occurred_at,
                    retry_actor_id, retry_authority_evidence_id
                ) VALUES (
                    :tenantId, :runId, :sequence, :eventId, 'RETRY_STARTED',
                    :fromState, :toState, :replacementRunId, :occurredAt,
                    :retryActorId, :retryAuthorityEvidenceId
                )
                RETURNING recorded_at
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", event.failedRunId.value)
            .param("sequence", event.sequence)
            .param("eventId", event.id.value)
            .param("fromState", event.fromState.name)
            .param("toState", event.toState.name)
            .param("replacementRunId", event.replacementRunId.value)
            .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .param("retryActorId", event.authorization?.actorId?.value)
            .param("retryAuthorityEvidenceId", event.authorization?.authorityEvidenceId?.value)
            .query(OffsetDateTime::class.java)
            .single()

    private fun updateState(
        tenantId: TenantId,
        event: ResolutionRunRetryStarted,
    ): ResolutionRunStateSnapshot {
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
                .param("runId", event.failedRunId.value)
                .param("expectedVersion", event.sequence - 1)
                .update()
        check(updated == 1) { "resolution run state changed before retry projection" }
        return ResolutionRunStateSnapshot(
            event.failedRunId,
            event.toState,
            event.sequence,
            event.occurredAt,
        )
    }
}

private fun ResolutionRunRetryRow.toStoredRetry(): StoredResolutionRunRetry =
    try {
        StoredResolutionRunRetry(
            ResolutionRunRetryStarted.rehydrate(
                ResolutionRunRetrySnapshot(
                    ResolutionRunEventId(eventId),
                    ResolutionRunId(runId),
                    ResolutionRunId(replacementRunId),
                    sequence,
                    ResolutionRunState.valueOf(fromState),
                    ResolutionRunState.valueOf(toState),
                    retryAuthorization(),
                    occurredAt.toInstant(),
                ),
            ),
            recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored resolution run retry is invalid", exception)
    }

private data class ResolutionRunRetryRow(
    val eventId: UUID,
    val runId: UUID,
    val replacementRunId: UUID,
    val sequence: Long,
    val fromState: String,
    val toState: String,
    val retryActorId: UUID?,
    val retryAuthorityEvidenceId: UUID?,
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun retryAuthorization() =
        if (retryActorId == null && retryAuthorityEvidenceId == null) {
            null
        } else {
            requireNotNull(retryActorId) { "stored retry actor is missing" }
            requireNotNull(retryAuthorityEvidenceId) { "stored retry authority evidence is missing" }
            ResolutionRunRetryAuthorization(
                HumanActorId(retryActorId),
                ApprovalAuthorityEvidenceId(retryAuthorityEvidenceId),
            )
        }
}
