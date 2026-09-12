package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineEntry
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TimelineObservation
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** PostgreSQL query adapter for the technology-neutral [CaseTimelineRepository] port. */
@Repository
class PostgresCaseTimelineRepository(
    private val jdbcClient: JdbcClient,
) : CaseTimelineRepository {
    override fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseTimeline? {
        // One statement gives the header and entries one PostgreSQL snapshot without a read transaction.
        val rows =
            jdbcClient
                .sql(
                    """
                    SELECT
                        c.case_id,
                        c.goal,
                        c.status,
                        c.stream_version AS case_stream_version,
                        e.stream_version AS entry_stream_version,
                        e.entry_type,
                        e.summary,
                        e.observation_id,
                        e.observation_origin_type,
                        e.observation_provider,
                        e.observation_reference,
                        e.observation_content,
                        e.occurred_at,
                        e.recorded_at
                    FROM cases c
                    INNER JOIN case_timeline_entries e
                        ON e.tenant_id = c.tenant_id
                        AND e.case_id = c.case_id
                    WHERE c.tenant_id = :tenantId
                        AND c.case_id = :caseId
                    ORDER BY e.stream_version
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("caseId", caseId.value)
                .query(DataClassRowMapper(CaseTimelineRow::class.java))
                .list()
        val first = rows.firstOrNull() ?: return null

        return CaseTimeline(
            caseId = first.caseId,
            goal = first.goal,
            status = first.status,
            streamVersion = first.caseStreamVersion,
            entries = rows.map(CaseTimelineRow::toEntry),
        )
    }
}

private data class CaseTimelineRow(
    val caseId: UUID,
    val goal: String,
    val status: String,
    val caseStreamVersion: Long,
    val entryStreamVersion: Long,
    val entryType: String,
    val summary: String,
    val observationId: UUID,
    val observationOriginType: String,
    val observationProvider: String,
    val observationReference: String?,
    val observationContent: String,
    val occurredAt: OffsetDateTime,
    val recordedAt: OffsetDateTime,
) {
    fun toEntry() =
        CaseTimelineEntry(
            streamVersion = entryStreamVersion,
            eventType = entryType,
            summary = summary,
            occurredAt = occurredAt.toInstant(),
            recordedAt = recordedAt.toInstant(),
            observation =
                TimelineObservation(
                    id = observationId,
                    originType = observationOriginType,
                    provider = observationProvider,
                    reference = observationReference,
                    content = observationContent,
                ),
        )
}
