package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.TenantId
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.cases.application.NewCaseEvent
import org.ergon.controlplane.cases.application.StoredCaseEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class PostgresCaseEventStore(
    private val jdbcClient: JdbcClient,
    private val codec: CaseEventJsonCodec,
) : CaseEventStore {
    override fun load(
        tenantId: TenantId,
        caseId: CaseId,
    ): List<CaseEvent> =
        jdbcClient
            .sql(
                """
                select event_type, schema_version, payload::text
                from case_events
                where tenant_id = :tenantId and case_id = :caseId
                order by stream_version
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("caseId", caseId.value)
            .query { resultSet, _ ->
                codec.decode(
                    resultSet.getString("event_type"),
                    resultSet.getInt("schema_version"),
                    resultSet.getString("payload"),
                )
            }.list()

    override fun append(
        tenantId: TenantId,
        caseId: CaseId,
        expectedVersion: Long,
        events: List<NewCaseEvent>,
    ): List<StoredCaseEvent> {
        require(events.isNotEmpty()) { "at least one event is required" }
        lockStream(tenantId, caseId)
        val actualVersion = currentVersion(tenantId, caseId)
        if (actualVersion != expectedVersion) {
            throw ConcurrentCaseModificationException(expectedVersion, actualVersion)
        }

        return events.mapIndexed { index, newEvent ->
            val streamVersion = expectedVersion + index + 1
            val encoded = codec.encode(newEvent.event)
            val recordedAt =
                jdbcClient
                    .sql(
                        """
                        insert into case_events (
                            event_id, tenant_id, case_id, stream_version,
                            event_type, schema_version, payload, occurred_at
                        ) values (
                            :eventId, :tenantId, :caseId, :streamVersion,
                            :eventType, :schemaVersion, cast(:payload as jsonb), :occurredAt
                        )
                        returning recorded_at
                        """.trimIndent(),
                    ).param("eventId", newEvent.eventId)
                    .param("tenantId", tenantId.value)
                    .param("caseId", caseId.value)
                    .param("streamVersion", streamVersion)
                    .param("eventType", encoded.eventType)
                    .param("schemaVersion", encoded.schemaVersion)
                    .param("payload", encoded.payload)
                    .param("occurredAt", newEvent.event.occurredAt.atOffset(ZoneOffset.UTC))
                    .query(OffsetDateTime::class.java)
                    .single()
                    .toInstant()
            StoredCaseEvent(newEvent.eventId, streamVersion, newEvent.event, recordedAt)
        }
    }

    private fun lockStream(
        tenantId: TenantId,
        caseId: CaseId,
    ) {
        jdbcClient
            .sql("select pg_advisory_xact_lock(hashtextextended(:streamKey, 0))")
            .param("streamKey", "${tenantId.value}:${caseId.value}")
            .query { _, _ -> Unit }
            .single()
    }

    private fun currentVersion(
        tenantId: TenantId,
        caseId: CaseId,
    ): Long =
        jdbcClient
            .sql(
                """
                select coalesce(max(stream_version), 0)
                from case_events
                where tenant_id = :tenantId and case_id = :caseId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("caseId", caseId.value)
            .query(Long::class.java)
            .single()
}
