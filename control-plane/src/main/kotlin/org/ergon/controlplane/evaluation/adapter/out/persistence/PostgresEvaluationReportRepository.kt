package org.ergon.controlplane.evaluation.adapter.out.persistence

import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationReportCodec
import org.ergon.controlplane.evaluation.application.EvaluationReportConflict
import org.ergon.controlplane.evaluation.application.EvaluationReportRegistration
import org.ergon.controlplane.evaluation.application.EvaluationReportRepository
import org.ergon.controlplane.evaluation.application.EvaluationReportUnavailable
import org.ergon.controlplane.evaluation.application.PreparedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.StoredEvaluationReport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID

/** Atomic command receipt: conflict wait then fresh read preserves the winner, without a transaction around replay. */
@Repository
class PostgresEvaluationReportRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val codec: EvaluationReportCodec,
) : EvaluationReportRepository {
    override fun record(
        command: EvaluationCommand,
        report: PreparedEvaluationReport,
        actor: UUID,
    ): EvaluationReportRegistration =
        checkNotNull(
            transactions.execute {
                val validated = codec.decode(report.definition)
                require(validated.digest == report.digest && validated.content == report.content)
                val tenant = command.fixture.tenantId
                val available =
                    jdbc
                        .sql(
                            """
                            SELECT EXISTS (SELECT 1 FROM human_actors WHERE tenant_id = :tenant AND actor_id = :actor)
                                AND EXISTS (SELECT 1 FROM simulation_fixture_revisions
                                    WHERE tenant_id = :tenant AND fixture_key = :key AND revision = :revision AND digest = :digest)
                            """.trimIndent(),
                        ).param("tenant", tenant)
                        .param("actor", actor)
                        .param("key", command.fixture.key)
                        .param("revision", command.fixture.revision)
                        .param("digest", report.content.fixtureDigest)
                        .query(Boolean::class.java)
                        .single()
                if (!available) throw EvaluationReportUnavailable()
                val created =
                    jdbc
                        .sql(
                            """
                            INSERT INTO simulation_evaluation_reports
                                (tenant_id, evaluation_id, fixture_key, fixture_revision, fixture_digest,
                                    report_digest, definition, passed, submitted_by)
                            VALUES (:tenant, :id, :key, :revision, :fixture, :digest, :definition, :passed, :actor)
                            ON CONFLICT (tenant_id, evaluation_id) DO NOTHING
                            """.trimIndent(),
                        ).param("tenant", tenant)
                        .param("id", command.id)
                        .param("key", command.fixture.key)
                        .param("revision", command.fixture.revision)
                        .param("fixture", report.content.fixtureDigest)
                        .param("digest", report.digest)
                        .param("definition", report.definition)
                        .param("passed", report.content.passed)
                        .param("actor", actor)
                        .update() == 1
                val stored = checkNotNull(find(tenant, command.id))
                if (stored.command != command || stored.definition != report.definition) {
                    throw EvaluationReportConflict()
                }
                EvaluationReportRegistration(stored, created)
            },
        )

    override fun find(
        tenant: UUID,
        id: UUID,
    ): StoredEvaluationReport? =
        jdbc
            .sql(
                """
                SELECT fixture_key, fixture_revision, fixture_digest, report_digest, definition, passed, submitted_by, recorded_at
                FROM simulation_evaluation_reports WHERE tenant_id = :tenant AND evaluation_id = :id
                """.trimIndent(),
            ).param("tenant", tenant)
            .param("id", id)
            .query { row, _ ->
                val report = codec.decode(row.getString("definition"))
                check(report.digest == row.getString("report_digest"))
                check(report.content.fixtureDigest == row.getString("fixture_digest"))
                check(report.content.passed == row.getBoolean("passed"))
                StoredEvaluationReport(
                    EvaluationCommand(
                        SimulationFixtureIdentity(
                            tenant,
                            row.getString("fixture_key"),
                            row.getString("fixture_revision"),
                        ),
                        id,
                    ),
                    report.content.fixtureDigest,
                    report.digest,
                    report.definition,
                    report.content.passed,
                    row.getObject("submitted_by", UUID::class.java),
                    row.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }.optional()
            .orElse(null)
}
