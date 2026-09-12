package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseEvent
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.cases.application.NewCaseEvent
import org.ergon.controlplane.cases.application.StoredCaseEvent
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Postgres-backed [CaseEventStore].
 *
 * [append] takes a transaction-scoped advisory lock on the tenant/case pair
 * before checking the expected-version contract, so concurrent appends to
 * the same stream serialize instead of racing. Callers must invoke it inside
 * [TransactionRunner.required]; the adapter does not start a transaction
 * itself. The
 * `uq_case_events_stream_version` unique constraint is the backstop if that
 * lock is ever bypassed — in that case a caller sees a raw
 * `DataIntegrityViolationException` instead of
 * [ConcurrentCaseModificationException].
 */
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
                SELECT event_type, schema_version, payload::TEXT
                FROM case_events
                WHERE tenant_id = :tenantId AND case_id = :caseId
                ORDER BY stream_version
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
        require(expectedVersion >= 0) { "expected version must not be negative" }
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
                        INSERT INTO case_events (
                            event_id, tenant_id, case_id, stream_version,
                            event_type, schema_version, payload, occurred_at
                        ) VALUES (
                            :eventId, :tenantId, :caseId, :streamVersion,
                            :eventType, :schemaVersion, CAST(:payload AS JSONB), :occurredAt
                        )
                        RETURNING recorded_at
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
        // This lock is released at transaction end. Outside a transaction it would be released immediately.
        // The fixed-width UUID pair is unambiguous; its 64-bit hash scopes one stream lock.
        jdbcClient
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:streamKey, 0))")
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
                SELECT coalesce(max(stream_version), 0)
                FROM case_events
                WHERE tenant_id = :tenantId AND case_id = :caseId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("caseId", caseId.value)
            .query(Long::class.java)
            .single()
}
