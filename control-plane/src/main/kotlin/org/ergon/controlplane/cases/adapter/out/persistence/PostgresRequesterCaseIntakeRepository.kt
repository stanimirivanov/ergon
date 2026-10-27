package org.ergon.controlplane.cases.adapter.out.persistence

import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.CaseOpened
import org.ergon.controlplane.cases.application.RequesterCaseIntakeRepository
import org.ergon.controlplane.cases.application.StoredRequesterCaseOpening
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Reuses existing immutable case events/bindings; no receipt table, projection rewrite or ownership inference. */
@Repository
class PostgresRequesterCaseIntakeRepository(
    private val jdbc: JdbcClient,
    private val codec: CaseEventJsonCodec,
) : RequesterCaseIntakeRepository {
    override fun lock(
        tenantId: TenantId,
        caseId: CaseId,
    ) {
        // Must match PostgresCaseEventStore: intake must serialize with legacy openings and later appends too.
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:streamKey, 0))")
            .param("streamKey", "${tenantId.value}:${caseId.value}")
            .query { _, _ -> Unit }
            .single()
    }

    override fun find(
        tenantId: TenantId,
        caseId: CaseId,
    ): StoredRequesterCaseOpening? =
        jdbc
            .sql(
                """
                SELECT e.event_type, e.schema_version, e.payload::TEXT, e.recorded_at,
                    CASE WHEN r.source_reference = :source THEN r.requester_actor_id END AS intake_actor_id
                FROM case_events e
                LEFT JOIN case_requesters r ON r.tenant_id = e.tenant_id AND r.case_id = e.case_id
                WHERE e.tenant_id = :tenant AND e.case_id = :case AND e.stream_version = 1
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("case", caseId.value)
            .param("source", INTAKE_SOURCE)
            .query { row, _ ->
                val event = codec.decode(row.getString("event_type"), row.getInt("schema_version"), row.getString("payload"))
                check(event is CaseOpened) { "Case stream must begin with an opening" }
                StoredRequesterCaseOpening(
                    event,
                    row.getObject("intake_actor_id", UUID::class.java)?.let(::HumanActorId),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)

    override fun bind(
        tenantId: TenantId,
        caseId: CaseId,
        actorId: HumanActorId,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO case_requesters (tenant_id, case_id, requester_actor_id, source_reference)
                VALUES (:tenant, :case, :actor, :source)
                """.trimIndent(),
            ).param("tenant", tenantId.value)
            .param("case", caseId.value)
            .param("actor", actorId.value)
            .param("source", INTAKE_SOURCE)
            .update()
    }

    private companion object {
        // Trusted producer provenance, never supplied by a browser or inferred from approval evidence.
        const val INTAKE_SOURCE = "requester-intake:v1"
    }
}
