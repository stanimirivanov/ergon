package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.AssertionResult
import org.ergon.evaluation.domain.SimulationArtifact
import java.time.Instant
import java.util.UUID

/** Explicit browser projection: no raw fixture, observation values, submitting actor or authority provenance. */
data class SimulationReportSummary(
    val reportId: UUID,
    val fixture: SimulationArtifact,
    val reportDigest: String,
    val passed: Boolean,
    val recordedAt: Instant,
)

data class SimulationReportPage(
    val items: List<SimulationReportSummary>,
    val nextAfter: UUID?,
)

data class SimulationReportDetail(
    val summary: SimulationReportSummary,
    val evaluator: SimulationArtifact,
    val assertions: SimulationArtifact,
    val checks: List<AssertionResult>,
    val budgets: EvaluationBudgetSummary?,
)

fun interface SimulationReportDiscovery {
    /** Newest-first stable keyset; after must identify a report in this tenant. Limit includes the lookahead row. */
    fun list(
        tenant: UUID,
        limit: Int,
        after: UUID?,
    ): List<SimulationReportSummary>
}

class SimulationLabReports(
    private val authority: SimulationLabAuthority,
    private val discovery: SimulationReportDiscovery,
    private val reports: EvaluationReportRepository,
    private val codec: EvaluationReportCodec,
) {
    fun list(
        tenant: UUID,
        actor: UUID,
        limit: Int,
        after: UUID?,
    ): SimulationReportPage {
        require(limit in 1..MAX_REPORT_PAGE)
        requireViewer(authority, tenant, actor)
        val rows = discovery.list(tenant, limit + 1, after)
        return SimulationReportPage(
            rows.take(limit),
            if (rows.size > limit) rows[limit - 1].reportId else null,
        )
    }

    fun get(
        tenant: UUID,
        actor: UUID,
        id: UUID,
    ): SimulationReportDetail {
        requireViewer(authority, tenant, actor)
        val stored = reports.find(tenant, id) ?: throw SimulationLabUnavailable()
        val content = codec.decode(stored.definition).content
        val fixture =
            SimulationArtifact(
                stored.command.fixture.key,
                stored.command.fixture.revision,
                stored.fixtureDigest,
            )
        return SimulationReportDetail(
            SimulationReportSummary(id, fixture, stored.reportDigest, stored.passed, stored.recordedAt),
            content.evaluator,
            content.assertions,
            content.checks,
            content.budgets,
        )
    }
}

internal fun requireViewer(
    authority: SimulationLabAuthority,
    tenant: UUID,
    actor: UUID,
) {
    if (!authority.canView(tenant, actor)) throw SimulationLabUnavailable()
}

internal const val MAX_REPORT_PAGE = 50
