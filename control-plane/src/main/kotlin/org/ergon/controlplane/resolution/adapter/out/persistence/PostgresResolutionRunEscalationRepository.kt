package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRecording
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.StoredResolutionRunEscalation
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRetryDenialReason
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationAuthorization
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEscalationRequested
import org.ergon.resolution.domain.ResolutionRunEscalationSnapshot
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

/** PostgreSQL append and replay adapter for exhausted-run escalation requests. */
@Repository
class PostgresResolutionRunEscalationRepository(
    private val jdbcClient: JdbcClient,
) : ResolutionRunEscalationRepository {
    override fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunEscalation? =
        jdbcClient
            .sql(
                """
                SELECT event_id, run_id, sequence, event_type, from_state, to_state,
                    escalation_reason, escalation_actor_id, escalation_authority_evidence_id,
                    escalation_policy_revision, escalation_source_attempt_number,
                    escalation_maximum_attempts, occurred_at, recorded_at
                FROM resolution_run_events
                WHERE tenant_id = :tenantId AND run_id = :runId
                    AND event_type = 'ESCALATION_REQUESTED'
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(ResolutionRunEscalationRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredEscalation()

    override fun append(
        tenantId: TenantId,
        event: ResolutionRunEscalationRequested,
    ): ResolutionRunEscalationRecording {
        val recordedAt = insertEvent(tenantId, event)
        val state = updateState(tenantId, event)
        return ResolutionRunEscalationRecording(
            StoredResolutionRunEscalation(event, recordedAt.toInstant()),
            state,
            created = true,
        )
    }

    private fun insertEvent(
        tenantId: TenantId,
        event: ResolutionRunEscalationRequested,
    ): OffsetDateTime =
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_run_events (
                    tenant_id, run_id, sequence, event_id, event_type, from_state, to_state,
                    escalation_reason, escalation_actor_id, escalation_authority_evidence_id,
                    escalation_policy_revision, escalation_source_attempt_number,
                    escalation_maximum_attempts, occurred_at
                ) VALUES (
                    :tenantId, :runId, :sequence, :eventId, :eventType, :fromState, :toState,
                    :reason, :actorId, :authorityEvidenceId, :policyRevision,
                    :sourceAttemptNumber, :maximumAttempts, :occurredAt
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
            .param("reason", event.reason.name)
            .param("actorId", event.authorization.actorId.value)
            .param("authorityEvidenceId", event.authorization.authorityEvidenceId.value)
            .param("policyRevision", event.retryDenial.revision.value)
            .param("sourceAttemptNumber", event.retryDenial.sourceAttemptNumber)
            .param("maximumAttempts", event.retryDenial.maximumAttempts)
            .param("occurredAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .query(OffsetDateTime::class.java)
            .single()

    private fun updateState(
        tenantId: TenantId,
        event: ResolutionRunEscalationRequested,
    ): ResolutionRunStateSnapshot {
        val updated =
            jdbcClient
                .sql(
                    """
                    UPDATE resolution_run_states
                    SET state = :state, version = :version, updated_at = :updatedAt
                    WHERE tenant_id = :tenantId AND run_id = :runId AND version = :expectedVersion
                    """.trimIndent(),
                ).param("state", event.toState.name)
                .param("version", event.sequence)
                .param("updatedAt", event.occurredAt.atOffset(ZoneOffset.UTC))
                .param("tenantId", tenantId.value)
                .param("runId", event.runId.value)
                .param("expectedVersion", event.sequence - 1)
                .update()
        check(updated == 1) { "resolution run state changed before escalation projection" }
        return ResolutionRunStateSnapshot(event.runId, event.toState, event.sequence, event.occurredAt)
    }
}

private fun ResolutionRunEscalationRow.toStoredEscalation(): StoredResolutionRunEscalation =
    try {
        StoredResolutionRunEscalation(
            ResolutionRunEscalationRequested.rehydrate(
                ResolutionRunEscalationSnapshot(
                    ResolutionRunEventId(eventId),
                    ResolutionRunId(runId),
                    sequence,
                    ResolutionRunEventType.valueOf(eventType),
                    ResolutionRunState.valueOf(fromState),
                    ResolutionRunState.valueOf(toState),
                    ResolutionRunEscalationReason.valueOf(escalationReason),
                    ResolutionRunEscalationAuthorization(
                        HumanActorId(escalationActorId),
                        ApprovalAuthorityEvidenceId(escalationAuthorityEvidenceId),
                    ),
                    ResolutionRetryEligibility.Denied(
                        ResolutionRetryPolicyRevision.of(escalationPolicyRevision),
                        escalationSourceAttemptNumber,
                        escalationMaximumAttempts,
                        ResolutionRetryDenialReason.ATTEMPT_LIMIT_REACHED,
                    ),
                    occurredAt.toInstant(),
                ),
            ),
            recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored resolution run escalation is invalid", exception)
    }

private data class ResolutionRunEscalationRow(
    val eventId: UUID,
    val runId: UUID,
    val sequence: Long,
    val eventType: String,
    val fromState: String,
    val toState: String,
    val escalationReason: String,
    val escalationActorId: UUID,
    val escalationAuthorityEvidenceId: UUID,
    val escalationPolicyRevision: String,
    val escalationSourceAttemptNumber: Int,
    val escalationMaximumAttempts: Int,
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
