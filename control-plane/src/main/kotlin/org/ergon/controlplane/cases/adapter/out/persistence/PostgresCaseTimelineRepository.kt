package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.TenantId
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineEntry
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TimelineObservation
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class PostgresCaseTimelineRepository(
    private val jdbcClient: JdbcClient,
) : CaseTimelineRepository {
    override fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): CaseTimeline? {
        val header =
            jdbcClient
                .sql(
                    """
                    select goal, status, stream_version
                    from cases
                    where tenant_id = :tenantId and case_id = :caseId
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("caseId", caseId.value)
                .query { resultSet, _ ->
                    CaseHeader(
                        resultSet.getString("goal"),
                        resultSet.getString("status"),
                        resultSet.getLong("stream_version"),
                    )
                }.optional()
                .orElse(null) ?: return null

        val entries =
            jdbcClient
                .sql(
                    """
                    select stream_version, entry_type, summary,
                           observation_id, observation_origin_type, observation_provider,
                           observation_reference, observation_content, occurred_at, recorded_at
                    from case_timeline_entries
                    where tenant_id = :tenantId and case_id = :caseId
                    order by stream_version
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("caseId", caseId.value)
                .query { resultSet, _ ->
                    CaseTimelineEntry(
                        streamVersion = resultSet.getLong("stream_version"),
                        eventType = resultSet.getString("entry_type"),
                        summary = resultSet.getString("summary"),
                        occurredAt = resultSet.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                        recordedAt = resultSet.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                        observation =
                            TimelineObservation(
                                id = resultSet.getObject("observation_id", UUID::class.java),
                                originType = resultSet.getString("observation_origin_type"),
                                provider = resultSet.getString("observation_provider"),
                                reference = resultSet.getString("observation_reference"),
                                content = resultSet.getString("observation_content"),
                            ),
                    )
                }.list()

        return CaseTimeline(caseId.value, header.goal, header.status, header.streamVersion, entries)
    }
}

private data class CaseHeader(
    val goal: String,
    val status: String,
    val streamVersion: Long,
)
