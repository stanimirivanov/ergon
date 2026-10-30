package org.ergon.controlplane.evaluation.adapter.out.persistence

import org.ergon.controlplane.evaluation.application.MAX_REPORT_PAGE
import org.ergon.controlplane.evaluation.application.SimulationLabUnavailable
import org.ergon.controlplane.evaluation.application.SimulationReportDiscovery
import org.ergon.controlplane.evaluation.application.SimulationReportSummary
import org.ergon.evaluation.domain.SimulationArtifact
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class PostgresSimulationReportDiscovery(
    private val jdbc: JdbcClient,
) : SimulationReportDiscovery {
    override fun list(
        tenant: UUID,
        limit: Int,
        after: UUID?,
    ): List<SimulationReportSummary> {
        require(limit in 1..(MAX_REPORT_PAGE + 1))
        val at =
            after?.let {
                jdbc
                    .sql(
                        "SELECT recorded_at FROM simulation_evaluation_reports " +
                            "WHERE tenant_id = :tenant AND evaluation_id = :id",
                    ).param("tenant", tenant)
                    .param("id", it)
                    .query(OffsetDateTime::class.java)
                    .optional()
                    .orElse(null)
                    ?: throw SimulationLabUnavailable()
            }
        val cursor = if (after == null) "" else "AND (recorded_at, evaluation_id) < (:at, :after)"
        val query =
            jdbc
                .sql(
                    """
                    SELECT evaluation_id,
                         fixture_key,
                         fixture_revision,
                         fixture_digest,
                         report_digest,
                         passed,
                         recorded_at
                    FROM simulation_evaluation_reports WHERE tenant_id = :tenant $cursor
                    ORDER BY recorded_at DESC, evaluation_id DESC LIMIT :limit
                    """.trimIndent(),
                ).param("tenant", tenant)
                .param("limit", limit)
        if (after != null) query.param("at", at).param("after", after)
        return query
            .query { row, _ ->
                SimulationReportSummary(
                    row.getObject("evaluation_id", UUID::class.java),
                    SimulationArtifact(
                        row.getString("fixture_key"),
                        row.getString("fixture_revision"),
                        row.getString("fixture_digest"),
                    ),
                    row.getString("report_digest"),
                    row.getBoolean("passed"),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }
}
