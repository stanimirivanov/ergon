package org.ergon.controlplane.evaluation.application

import org.ergon.evaluation.domain.EligibilityReason
import org.ergon.evaluation.domain.GateEvidence
import org.ergon.evaluation.domain.GateReport
import org.ergon.evaluation.domain.PromotionEligibility
import org.ergon.evaluation.domain.PromotionEligibilityEvaluator
import org.ergon.evaluation.domain.SimulationArtifact
import java.util.UUID

data class SelectedEvaluationReport(
    val fixture: SimulationArtifact,
    val reportId: UUID,
    val reportDigest: String,
) {
    init {
        require(reportDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

data class EligibilityRequest(
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val reports: List<SelectedEvaluationReport>,
) {
    init {
        require(reports.size <= MAX_PROMOTION_REPORTS)
        reports.forEach { requireNotNull(it) }
        require(reports.map { it.fixture }.distinct().size == reports.size)
    }
}

/** Exact evidence coordinates for later revalidation; this read-only assessment is never a promotion receipt. */
data class EligibilityAssessment(
    val suite: SimulationArtifact,
    val contract: SimulationArtifact,
    val policy: SimulationArtifact,
    val reports: List<SelectedEvaluationReport>,
    val result: PromotionEligibility,
)

class EvaluatePromotionEligibility(
    private val suites: EvaluationSuites,
    private val documents: GateDocumentCodec,
    private val reports: EvaluationReportRepository,
    private val reportCodec: EvaluationReportCodec,
    private val fixtures: SimulationFixtureRepository,
    private val fixtureCodec: SimulationFixtureDecoder,
) {
    /** Resolve explicit IDs within the tenant; no fallback to a newer or passing report. */
    fun assess(
        identity: GateDocumentIdentity,
        request: EligibilityRequest,
    ): EligibilityAssessment {
        val stored = suites.get(GateDocumentKind.SUITE, identity)
        val suite = checkNotNull(documents.decode(GateDocumentKind.SUITE, stored.definition.toByteArray()).suite)
        val policyRecord =
            suites.get(
                GateDocumentKind.POLICY,
                identity.copy(key = suite.policy.key, revision = suite.policy.revision),
            )
        check(policyRecord.digest == suite.policy.sha256)
        val policyBytes = policyRecord.definition.toByteArray()
        val policy = checkNotNull(documents.decode(GateDocumentKind.POLICY, policyBytes).policy)
        val storedReports = reports.findReports(identity.tenant, request.reports.map { it.reportId }.toSet())
        val storedFixtures =
            fixtures.findFixtures(
                identity.tenant,
                request.reports.map { fixtureIdentity(identity.tenant, it) }.toSet(),
            )
        val evidence =
            request.reports.associate {
                val fixture = storedFixtures[fixtureIdentity(identity.tenant, it)]
                it.fixture to evidence(it, storedReports[it.reportId], fixture)
            }
        return EligibilityAssessment(
            stored.reference(),
            request.contract,
            request.policy,
            java.util.List.copyOf(request.reports),
            PromotionEligibilityEvaluator.assess(suite, policy, request.contract, request.policy, evidence),
        )
    }

    private fun evidence(
        selected: SelectedEvaluationReport,
        stored: StoredEvaluationReport?,
        fixture: StoredSimulationFixture?,
    ): GateEvidence {
        val rejection =
            when {
                stored == null || fixture == null -> EligibilityReason.MISSING_REPORT

                stored.command.fixture != fixture.identity || stored.fixtureDigest != selected.fixture.sha256 ||
                    stored.reportDigest != selected.reportDigest -> EligibilityReason.REPORT_IDENTITY_MISMATCH

                else -> null
            }
        if (rejection != null) return GateEvidence(null, rejection)
        return project(checkNotNull(fixture), checkNotNull(stored))
    }

    private fun fixtureIdentity(
        tenant: UUID,
        selected: SelectedEvaluationReport,
    ): SimulationFixtureIdentity = SimulationFixtureIdentity(tenant, selected.fixture.key, selected.fixture.revision)

    private fun project(
        fixture: StoredSimulationFixture,
        stored: StoredEvaluationReport,
    ): GateEvidence {
        val manifest = fixtureCodec.decode(fixture.definition.toByteArray()).fixture
        val content = reportCodec.decode(stored.definition).content
        check(fixture.digest == manifest.semanticDigest())
        check(content.fixtureDigest == fixture.digest && content.evaluator == manifest.evaluator)
        check(content.assertions == manifest.assertions && content.budget == manifest.budget)
        val budgets = content.budgets
        return GateEvidence(
            GateReport(
                content.evaluator,
                content.passed,
                content.checks.associate { it.id to it.status },
                budgets?.cost?.let { it.limit != null && it.status == "PASS" } ?: false,
                budgets?.simulatedLatency?.let { it.limit != null && it.status == "PASS" } ?: false,
            ),
            null,
        )
    }
}

internal const val MAX_PROMOTION_REPORTS = 50
