package org.ergon.controlplane.resolution.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAcceptanceRecording
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultRecording
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredResolutionOutcomeProofAcceptance
import org.ergon.controlplane.resolution.application.StoredResolutionRunCapabilityResult
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.ResolutionOutcomeProofAccepted
import org.ergon.resolution.domain.ResolutionOutcomeProofAcceptedSnapshot
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
    override fun findState(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): ResolutionRunStateSnapshot? =
        jdbcClient
            .sql(
                """
                SELECT run_id, state, version, updated_at
                FROM resolution_run_states
                WHERE tenant_id = :tenantId AND run_id = :runId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(ResolutionRunStateRow::class.java))
            .optional()
            .getOrNull()
            ?.toSnapshot()

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

    override fun findAcceptedProof(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionOutcomeProofAcceptance? =
        jdbcClient
            .sql(
                """
                SELECT
                    acceptance.run_event_id, acceptance.run_id,
                    acceptance.run_event_sequence, event.from_state, event.to_state,
                    acceptance.run_case_stream_version,
                    acceptance.case_stream_version, acceptance.fact_type,
                    acceptance.expected_value, acceptance.fact_id,
                    acceptance.observation_id, acceptance.observation_stream_version,
                    acceptance.fact_stream_version, acceptance.accepted_at,
                    event.recorded_at
                FROM resolution_outcome_proof_acceptances acceptance
                JOIN resolution_run_events event
                    ON event.tenant_id = acceptance.tenant_id
                    AND event.run_id = acceptance.run_id
                    AND event.sequence = acceptance.run_event_sequence
                    AND event.event_id = acceptance.run_event_id
                WHERE acceptance.tenant_id = :tenantId AND acceptance.run_id = :runId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", runId.value)
            .query(DataClassRowMapper(ResolutionOutcomeProofAcceptanceRow::class.java))
            .optional()
            .getOrNull()
            ?.toStoredAcceptance()

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

    override fun appendAcceptedProof(
        tenantId: TenantId,
        caseId: CaseId,
        event: ResolutionOutcomeProofAccepted,
    ): ResolutionOutcomeProofAcceptanceRecording {
        val recordedAt = insertAcceptedEvent(tenantId, event)
        insertAcceptedProof(tenantId, caseId, event)
        val currentState = updateState(tenantId, event)
        return ResolutionOutcomeProofAcceptanceRecording(
            storedEvent = StoredResolutionOutcomeProofAcceptance(event, recordedAt.toInstant()),
            currentState = currentState,
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

    private fun insertAcceptedEvent(
        tenantId: TenantId,
        event: ResolutionOutcomeProofAccepted,
    ): OffsetDateTime =
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_run_events (
                    tenant_id, run_id, sequence, event_id, event_type,
                    from_state, to_state, occurred_at
                ) VALUES (
                    :tenantId, :runId, :sequence, :eventId, :eventType,
                    :fromState, :toState, :occurredAt
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
            .param("occurredAt", event.acceptedAt.atOffset(ZoneOffset.UTC))
            .query(OffsetDateTime::class.java)
            .single()

    private fun insertAcceptedProof(
        tenantId: TenantId,
        caseId: CaseId,
        event: ResolutionOutcomeProofAccepted,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO resolution_outcome_proof_acceptances (
                    tenant_id, run_id, run_event_sequence, run_event_id,
                    case_id, run_case_stream_version, case_stream_version, fact_type,
                    expected_value, actual_value, fact_id, observation_id,
                    observation_stream_version, fact_stream_version, accepted_at
                ) VALUES (
                    :tenantId, :runId, :sequence, :eventId,
                    :caseId, :runCaseStreamVersion, :caseStreamVersion, :factType,
                    :expectedValue, :actualValue, :factId, :observationId,
                    :observationStreamVersion, :factStreamVersion, :acceptedAt
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("runId", event.runId.value)
            .param("sequence", event.sequence)
            .param("eventId", event.id.value)
            .param("caseId", caseId.value)
            .param("runCaseStreamVersion", event.runCaseStreamVersion)
            .param("caseStreamVersion", event.caseStreamVersion)
            .param("factType", event.fact.value)
            .param("expectedValue", event.expectedValue.value)
            .param("actualValue", event.expectedValue.value)
            .param("factId", event.factId.value)
            .param("observationId", event.observationId.value)
            .param("observationStreamVersion", event.observationStreamVersion)
            .param("factStreamVersion", event.factStreamVersion)
            .param("acceptedAt", event.acceptedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun updateState(
        tenantId: TenantId,
        event: ResolutionOutcomeProofAccepted,
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
                .param("updatedAt", event.acceptedAt.atOffset(ZoneOffset.UTC))
                .param("tenantId", tenantId.value)
                .param("runId", event.runId.value)
                .param("expectedVersion", event.sequence - 1)
                .update()
        check(updated == 1) { "resolution run state changed before accepted proof projection" }
        return ResolutionRunStateSnapshot(event.runId, event.toState, event.sequence, event.acceptedAt)
    }
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

private fun ResolutionOutcomeProofAcceptanceRow.toStoredAcceptance(): StoredResolutionOutcomeProofAcceptance =
    try {
        StoredResolutionOutcomeProofAcceptance(
            event =
                ResolutionOutcomeProofAccepted.rehydrate(
                    ResolutionOutcomeProofAcceptedSnapshot(
                        id = ResolutionRunEventId(runEventId),
                        runId = ResolutionRunId(runId),
                        sequence = runEventSequence,
                        fromState = ResolutionRunState.valueOf(fromState),
                        toState = ResolutionRunState.valueOf(toState),
                        runCaseStreamVersion = runCaseStreamVersion,
                        caseStreamVersion = caseStreamVersion,
                        fact = ContractFactType.of(factType),
                        expectedValue = ContractFactValue.of(expectedValue),
                        factId = FactId(factId),
                        observationId = ObservationId(observationId),
                        observationStreamVersion = observationStreamVersion,
                        factStreamVersion = factStreamVersion,
                        acceptedAt = acceptedAt.toInstant(),
                    ),
                ),
            recordedAt = recordedAt.toInstant(),
        )
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("stored resolution outcome proof acceptance is invalid", exception)
    }

private data class ResolutionOutcomeProofAcceptanceRow(
    val runEventId: UUID,
    val runId: UUID,
    val runEventSequence: Long,
    val fromState: String,
    val toState: String,
    val runCaseStreamVersion: Long,
    val caseStreamVersion: Long,
    val factType: String,
    val expectedValue: String,
    val factId: UUID,
    val observationId: UUID,
    val observationStreamVersion: Long,
    val factStreamVersion: Long,
    val acceptedAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
)
