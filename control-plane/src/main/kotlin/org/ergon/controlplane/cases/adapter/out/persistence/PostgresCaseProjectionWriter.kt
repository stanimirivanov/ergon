package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseOpened
import org.ergon.cases.domain.ErgonCase
import org.ergon.cases.domain.ObservationOriginType
import org.ergon.cases.domain.ObservationRecorded
import org.ergon.controlplane.cases.application.CaseProjectionWriter
import org.ergon.controlplane.cases.application.StoredCaseEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.ZoneOffset

/** Maintains current-case and timeline projections inside the event append transaction. */
@Repository
class PostgresCaseProjectionWriter(
    private val jdbcClient: JdbcClient,
) : CaseProjectionWriter {
    override fun project(
        case: ErgonCase,
        events: List<StoredCaseEvent>,
    ) {
        require(events.isNotEmpty()) { "at least one stored event is required" }
        when (val first = events.first().event) {
            is CaseOpened -> insertCase(case, first)
            is ObservationRecorded -> updateCase(case, events.first().streamVersion - 1, events.last())
        }
        events.forEach { insertTimelineEntry(case, it) }
    }

    private fun insertCase(
        case: ErgonCase,
        event: CaseOpened,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO cases (
                    tenant_id, case_id, goal, status, stream_version, opened_at, updated_at
                ) VALUES (
                    :tenantId, :caseId, :goal, :status, :streamVersion, :openedAt, :updatedAt
                )
                """.trimIndent(),
            ).param("tenantId", case.tenantId.value)
            .param("caseId", case.id.value)
            .param("goal", case.goal.value)
            .param("status", case.status.name)
            .param("streamVersion", case.streamVersion)
            .param("openedAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .param("updatedAt", event.occurredAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun updateCase(
        case: ErgonCase,
        expectedVersion: Long,
        lastEvent: StoredCaseEvent,
    ) {
        val changed =
            jdbcClient
                .sql(
                    """
                    UPDATE cases
                    SET status = :status, stream_version = :streamVersion, updated_at = :updatedAt
                    WHERE tenant_id = :tenantId
                        AND case_id = :caseId
                        AND stream_version = :expectedVersion
                    """.trimIndent(),
                ).param("status", case.status.name)
                .param("streamVersion", case.streamVersion)
                .param("updatedAt", lastEvent.event.occurredAt.atOffset(ZoneOffset.UTC))
                .param("tenantId", case.tenantId.value)
                .param("caseId", case.id.value)
                .param("expectedVersion", expectedVersion)
                .update()
        check(changed == 1) { "case projection was not at expected version $expectedVersion" }
    }

    private fun insertTimelineEntry(
        case: ErgonCase,
        stored: StoredCaseEvent,
    ) {
        val observation = stored.event.observation()
        jdbcClient
            .sql(
                """
                INSERT INTO case_timeline_entries (
                    tenant_id, case_id, stream_version, event_id, entry_type, summary,
                    observation_id, observation_origin_type, observation_provider,
                    observation_reference, observation_content, occurred_at, recorded_at
                ) VALUES (
                    :tenantId, :caseId, :streamVersion, :eventId, :entryType, :summary,
                    :observationId, :originType, :provider,
                    :reference, :content, :occurredAt, :recordedAt
                )
                """.trimIndent(),
            ).param("tenantId", case.tenantId.value)
            .param("caseId", case.id.value)
            .param("streamVersion", stored.streamVersion)
            .param("eventId", stored.eventId)
            .param("entryType", stored.event.eventType())
            .param("summary", stored.event.summary())
            .param("observationId", observation.id)
            .param("originType", observation.originType.name)
            .param("provider", observation.provider)
            .param("reference", observation.reference)
            .param("content", observation.content)
            .param("occurredAt", stored.event.occurredAt.atOffset(ZoneOffset.UTC))
            .param("recordedAt", stored.recordedAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun CaseEvent.eventType(): String =
        when (this) {
            is CaseOpened -> "CASE_OPENED"
            is ObservationRecorded -> "OBSERVATION_RECORDED"
        }

    private fun CaseEvent.summary(): String =
        when (this) {
            is CaseOpened -> "Case opened from requester observation"
            is ObservationRecorded -> "Connector observation recorded"
        }

    private fun CaseEvent.observation(): ObservationColumns =
        when (this) {
            is CaseOpened -> {
                ObservationColumns(
                    observationId,
                    observationOriginType,
                    observationProvider,
                    observationReference,
                    observationContent,
                )
            }

            is ObservationRecorded -> {
                ObservationColumns(
                    observationId,
                    observationOriginType,
                    observationProvider,
                    observationReference,
                    observationContent,
                )
            }
        }
}

private data class ObservationColumns(
    val id: java.util.UUID,
    val originType: ObservationOriginType,
    val provider: String,
    val reference: String?,
    val content: String,
)
