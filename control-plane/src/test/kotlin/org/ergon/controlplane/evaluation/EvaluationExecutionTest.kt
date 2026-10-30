package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationBudgetDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationProgramJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.http.EvaluationReportController
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationExecution
import org.ergon.controlplane.evaluation.application.EvaluationReportConflict
import org.ergon.controlplane.evaluation.application.EvaluationReportRegistration
import org.ergon.controlplane.evaluation.application.EvaluationReportRepository
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.PreparedEvaluationReport
import org.ergon.controlplane.evaluation.application.PreparedSimulationFixture
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureRegistration
import org.ergon.controlplane.evaluation.application.SimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.StoredEvaluationReport
import org.ergon.controlplane.evaluation.application.StoredSimulationFixture
import org.ergon.evaluation.domain.AssertionStatus
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class EvaluationExecutionTest {
    private val f = EvaluationReportFixtures()

    @Test
    fun `fresh execution yields identical canonical semantic report independent of material order`() {
        val document = f.document()
        val first = f.runner.execute(f.bytes(document))
        val second = f.runner.execute(f.bytes(document.copy(materials = document.materials.reversed())))
        assertThat(first).isEqualTo(second)
        assertThat(first.content.passed).isTrue()
        assertThat(f.codec.decode(first.definition)).isEqualTo(first)
        assertThat(first.definition).doesNotContain("submittedBy", "recordedAt", "evaluationId")
        assertThat(first.content.checks.map { it.id }).containsExactly("fixture-outcome", "not-ready")
    }

    @Test
    fun `failed unsupported and missing required observations never become passed reports`() {
        val variants =
            listOf(
                f.document(listOf(f.check(expected = "FAILED"))),
                f.document(listOf(f.check(kind = "FUTURE"))),
                f.document(listOf(f.check(target = "missing"))),
                f.document(
                    listOf(
                        f.check(
                            kind = "NO_UNPROVED_CLOSURE",
                            expected = mapOf("fact" to "account.access.state", "value" to "ACTIVE"),
                        ),
                    ),
                    f.f.document(),
                ),
            )
        val reports = variants.map { f.runner.execute(f.bytes(it)) }
        assertThat(reports.map { it.content.passed }).containsOnly(false)
        assertThat(
            reports.take(3).map {
                it.content.checks
                    .last()
                    .status
            },
        ).containsExactly(AssertionStatus.FAIL, AssertionStatus.UNSUPPORTED, AssertionStatus.ERROR)
        val optional = f.document(listOf(f.check(), f.check(id = "optional", kind = "FUTURE", required = false)))
        assertThat(
            f.runner
                .execute(f.bytes(optional))
                .content.passed,
        ).isTrue()
    }

    @Test
    fun `manifest expectation cannot be bypassed and unsupported planning budgets remain unknown`() {
        val document = f.document()
        val manifest = document.manifest.copy(expectedOutcome = SimulationExpectedOutcome.VERIFIED)
        val changed = document.copy(manifest = manifest, digest = manifest.toDomain().semanticDigest())
        assertThat(
            f.runner
                .execute(f.bytes(changed))
                .content.passed,
        ).isFalse()
        val budget =
            EvaluationBudgetDocument(
                EvaluationProgramJsonDecoder.BUDGET_SCHEMA,
                "USD",
                0,
                0,
                emptyList(),
                null,
                null,
                emptyList(),
            )
        val result = f.runner.execute(f.bytes(f.budget(document, f.mapper.writeValueAsString(budget))))
        assertThat(
            result.content.budgets!!
                .cost.status,
        ).isEqualTo("UNKNOWN")
        assertThat(result.content.passed).isFalse()
    }

    @Test
    fun `strict material rejects malformed expectations duplicates coercion and forged report summaries`() {
        val document = f.document()
        val content = document.materials.single { it.reference == document.manifest.assertions }.content
        val invalid =
            listOf(
                content.replace("\"required\":true", "\"required\":\"true\""),
                content.dropLast(1) + ",\"passed\":true}",
                content + " {}",
            )
        invalid.forEach { value ->
            assertThatThrownBy { f.runner.execute(f.bytes(f.replace(document, document.manifest.assertions, value))) }
                .isInstanceOf(InvalidSimulationFixture::class.java)
        }
        assertThatThrownBy { f.runner.execute(f.bytes(f.document(listOf(f.check(), f.check())))) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        val failed = f.runner.execute(f.bytes(f.document(listOf(f.check(expected = "FAILED")))))
        assertThatThrownBy { f.codec.encode(failed.content.copy(passed = true)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `command actor and recording time do not affect digest and exact retry retains original metadata`() {
        val memory = EvaluationMemory(f)
        val first = memory.service.execute(memory.command, UUID(0, 10))
        val retry = memory.service.execute(memory.command, UUID(0, 11))
        assertThat(retry.created).isFalse()
        assertThat(retry.stored).isEqualTo(first.stored)
        val fresh = memory.service.execute(memory.command.copy(id = UUID(0, 12)), UUID(0, 13))
        assertThat(fresh.stored.reportDigest).isEqualTo(first.stored.reportDigest)
        assertThat(fresh.stored.recordedAt).isNotEqualTo(first.stored.recordedAt)
        assertThat(fresh.stored.submittedBy).isNotEqualTo(first.stored.submittedBy)
        assertThatThrownBy {
            memory.service.execute(
                memory.command.copy(fixture = memory.command.fixture.copy(key = "other")),
                UUID(0, 10),
            )
        }.isInstanceOf(EvaluationReportConflict::class.java)
        memory.definition = "{}"
        val size = memory.reports.size
        assertThatThrownBy { memory.service.execute(memory.command.copy(id = UUID.randomUUID()), UUID(0, 10)) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
        assertThat(memory.reports).hasSize(size)
    }

    @Test
    fun `internal HTTP accepts only fixture reference and protects absent foreign reports`() {
        val memory = EvaluationMemory(f)
        val mvc = MockMvcBuilders.standaloneSetup(EvaluationReportController(memory.service)).build()
        val tenant = memory.command.fixture.tenantId
        val id = memory.command.id
        val path = "/internal/v1/tenants/$tenant/simulation-fixtures/sample/revisions/1/evaluations/$id"
        mvc
            .perform(post(path).header("X-Ergon-Actor-Id", UUID.randomUUID()))
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(post(path).header("X-Ergon-Actor-Id", UUID.randomUUID())).andExpect(status().isOk)
        mvc
            .perform(post(path).header("X-Ergon-Actor-Id", UUID.randomUUID()).content("{\"passed\":true}"))
            .andExpect(status().isUnprocessableEntity)
        mvc.perform(get("/internal/v1/tenants/$tenant/simulation-evaluations/$id")).andExpect(status().isOk)
        mvc
            .perform(get("/internal/v1/tenants/${UUID.randomUUID()}/simulation-evaluations/$id"))
            .andExpect(status().isNotFound)
    }
}

private class EvaluationMemory(
    f: EvaluationReportFixtures,
) : SimulationFixtureRepository,
    EvaluationReportRepository {
    val command = EvaluationCommand(SimulationFixtureIdentity(f.f.tenant, "sample", "1"), UUID.randomUUID())
    var definition = f.packages.decode(f.bytes()).definition
    private val digest =
        f.packages
            .decode(f.bytes())
            .fixture
            .semanticDigest()
    val reports = mutableMapOf<Pair<UUID, UUID>, StoredEvaluationReport>()
    val service = EvaluationExecution(this, f.runner, this)

    override fun find(identity: SimulationFixtureIdentity): StoredSimulationFixture? =
        if (identity.tenantId == command.fixture.tenantId) {
            StoredSimulationFixture(identity, digest, definition, UUID(0, 1), Instant.EPOCH)
        } else {
            null
        }

    override fun register(
        identity: SimulationFixtureIdentity,
        prepared: PreparedSimulationFixture,
        actor: UUID,
    ): SimulationFixtureRegistration = error("unused")

    override fun find(
        tenant: UUID,
        id: UUID,
    ): StoredEvaluationReport? = reports[tenant to id]

    override fun record(
        command: EvaluationCommand,
        report: PreparedEvaluationReport,
        actor: UUID,
    ): EvaluationReportRegistration {
        val key = command.fixture.tenantId to command.id
        val old = reports[key]
        if (old != null) {
            if (old.command != command || old.definition != report.definition) throw EvaluationReportConflict()
            return EvaluationReportRegistration(old, false)
        }
        val stored =
            StoredEvaluationReport(
                command,
                report.content.fixtureDigest,
                report.digest,
                report.definition,
                report.content.passed,
                actor,
                Instant.EPOCH.plusSeconds(reports.size.toLong()),
            )
        reports[key] = stored
        return EvaluationReportRegistration(stored, true)
    }
}
