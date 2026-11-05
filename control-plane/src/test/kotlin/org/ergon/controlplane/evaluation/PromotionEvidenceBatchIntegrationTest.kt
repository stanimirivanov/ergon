package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.contracts.ContractPromotionDatabase
import org.ergon.controlplane.evaluation.application.EligibilityRequest
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.evaluation.domain.EligibilityReason
import org.ergon.evaluation.domain.SimulationArtifact
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.system.measureNanoTime

@Testcontainers(disabledWithoutDocker = true)
class PromotionEvidenceBatchIntegrationTest {
    @Test
    fun `maximum selection uses two tenant scoped evidence queries with equivalent outcomes`() {
        val reads = AtomicInteger()
        val db = ContractPromotionDatabase(postgres, decorate = { EvidenceCountingDataSource(it, reads) })
        val h = db.h
        val selected =
            (1..50).map { index ->
                val identity = h.fixtureId.copy(key = "fixture-$index")
                db.fixtures.register(identity, h.fixture, db.actor)
                val report = db.execution.execute(EvaluationCommand(identity, UUID.randomUUID()), db.actor).stored
                SelectedEvaluationReport(
                    SimulationArtifact(identity.key, identity.revision, report.fixtureDigest),
                    report.command.id,
                    report.reportDigest,
                )
            }
        val identity = h.suiteId.copy(revision = "maximum")
        db.suites.register(
            GateDocumentKind.SUITE,
            identity,
            h.suiteBytes(selected.map { h.member().copy(fixture = it.fixture) }),
            db.actor,
        )
        val request = EligibilityRequest(h.fixture.fixture.contract, h.policyRef(), selected)
        reads.set(0)
        val elapsed =
            measureNanoTime {
                assertThat(
                    db.eligibility
                        .assess(identity, request)
                        .result.eligible,
                ).isTrue()
            }
        println("Promotion evidence: selection=50 queries=${reads.get()} elapsedMicros=${elapsed / 1000}")
        assertThat(reads.get()).isEqualTo(2)

        val substituted = request.copy(reports = listOf(selected.first().copy(reportId = selected.last().reportId)))
        assertThat(
            db.eligibility
                .assess(identity, substituted)
                .result.scenarios
                .flatMap { it.reasons },
        ).contains(EligibilityReason.REPORT_IDENTITY_MISMATCH)
        val wrongReport = selected.first().copy(reportId = UUID.randomUUID())
        val missing = db.eligibility.assess(identity, request.copy(reports = listOf(wrongReport)))
        assertThat(missing.result.scenarios.flatMap { it.reasons }).contains(EligibilityReason.MISSING_REPORT)
    }

    @Test
    fun `bulk reads retain exact tuple identity tenant isolation and empty input semantics`() {
        val reads = AtomicInteger()
        val db = ContractPromotionDatabase(postgres, decorate = { EvidenceCountingDataSource(it, reads) })
        val first = db.h.fixtureId
        val second = first.copy(key = "other", revision = "2")
        db.fixtures.register(second, db.h.fixture, db.actor)
        val reports = setOf(db.h.reportId, UUID.randomUUID())
        assertThat(db.reports.findReports(db.tenant, reports).keys).containsExactly(db.h.reportId)
        val selected = setOf(first, second.copy(revision = first.revision))
        assertThat(db.fixtures.findFixtures(db.tenant, selected).keys).containsExactly(first)
        val foreign = UUID.randomUUID()
        assertThat(db.reports.findReports(foreign, reports)).isEmpty()
        assertThat(db.fixtures.findFixtures(foreign, setOf(first.copy(tenantId = foreign)))).isEmpty()
        assertThatThrownBy { db.fixtures.findFixtures(foreign, setOf(first)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        reads.set(0)
        assertThat(db.reports.findReports(db.tenant, emptySet())).isEmpty()
        assertThat(db.fixtures.findFixtures(db.tenant, emptySet())).isEmpty()
        assertThatThrownBy { db.reports.findReports(db.tenant, (1..51).map { UUID.randomUUID() }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            val oversized = (1..51).map { SimulationFixtureIdentity(db.tenant, "key-$it", "1") }.toSet()
            db.fixtures.findFixtures(db.tenant, oversized)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(reads.get()).isZero()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}

private class EvidenceCountingDataSource(
    data: DataSource,
    private val reads: AtomicInteger,
) : DelegatingDataSource(data) {
    override fun getConnection(): Connection {
        val connection = super.getConnection()
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "prepareStatement" &&
                (args?.firstOrNull() as? String)?.let {
                    it.contains("FROM simulation_evaluation_reports") ||
                        it.contains("FROM simulation_fixture_revisions")
                } == true
            ) {
                reads.incrementAndGet()
            }
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } as Connection
    }
}
