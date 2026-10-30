package org.ergon.controlplane.cases

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.controlplane.cases.application.CaseDocumentaryBundleUnavailable
import org.ergon.controlplane.cases.application.ConcurrentCaseModificationException
import org.ergon.controlplane.cases.application.OpenCaseCommand
import org.ergon.controlplane.cases.application.PinCaseDocumentaryBundleCommand
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.ResolutionRunNotReadyException
import org.ergon.resolution.domain.DocumentaryRequirementStatus
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.StepPolicyDecision
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class CaseDocumentaryBundleIntegrationTest {
    @Test
    fun `exact suitable attachments permit only ordinary approval gated run creation and retain history`() {
        val d = CaseDocumentaryDatabase(postgres)
        val case = d.openPreparedCase()
        assertThat(d.planner.plan(d.tenant.value, case).nextStep).isNull()
        assertThatThrownBy { d.runService.start(d.tenant.value, case, 4) }
            .isInstanceOf(ResolutionRunNotReadyException::class.java)
        d.attach(case)
        val pins = d.pins.find(d.tenant, CaseId(case), 5)
        assertThat(pins.single().attachedAt).isAfter(d.b.f.now)
        assertThat(d.pins.find(d.tenant, CaseId(case), 4)).isEmpty()
        val evaluated = d.readiness.evaluate(d.tenant.value, case) as CaseReadiness.Evaluated
        assertThat(evaluated.readiness).isInstanceOf(ResolutionReadiness.Ready::class.java)
        val start = d.runService.start(d.tenant.value, case, 5)
        assertThat(start.run.initialState).isEqualTo(ResolutionRunInitialState.WAITING_FOR_APPROVAL)
        d.b.db.accept(d.b.db.add(130))
        val after = d.readiness.evaluate(d.tenant.value, case) as CaseReadiness.Evaluated
        assertThat(after.readiness).isEqualTo(evaluated.readiness)
        assertThat(after.documentaryEvidence).isEqualTo(evaluated.documentaryEvidence)
        assertThat(
            d.runs
                .find(d.tenant, start.run.id)
                ?.run
                ?.caseStreamVersion,
        ).isEqualTo(5)
        for (operation in listOf(
            "UPDATE case_documentary_bundle_pins SET recorded_at = clock_timestamp()",
            "DELETE FROM case_documentary_bundle_pins",
        )) {
            assertThatThrownBy {
                d.b.db.jdbc
                    .sql(operation)
                    .update()
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `expired material attaches but cannot satisfy readiness at database attachment time`() {
        val d = CaseDocumentaryDatabase(postgres, broad = false)
        val case = d.openPreparedCase()
        d.attach(case)
        val evaluated = d.readiness.evaluate(d.tenant.value, case) as CaseReadiness.Evaluated
        val unavailable = evaluated.readiness as ResolutionReadiness.DocumentaryEvidenceUnavailable
        assertThat(unavailable.assessments.single().status).isEqualTo(DocumentaryRequirementStatus.UNSUITABLE)
        assertThat(d.planner.plan(d.tenant.value, case).nextStep).isNull()
        assertThatThrownBy { d.runService.start(d.tenant.value, case, 5) }
            .isInstanceOf(ResolutionRunNotReadyException::class.java)
        assertDirectRunDenied(d, case, 5, "UNSUITABLE_DOCUMENTARY_EVIDENCE")
    }

    @Test
    fun `tenant digest and stale version failures leave the event stream untouched`() {
        val d = CaseDocumentaryDatabase(postgres)
        val case = d.openPreparedCase()
        assertThatThrownBy { d.attach(case, 3) }.isInstanceOf(ConcurrentCaseModificationException::class.java)
        assertThatThrownBy { d.attach(case, selected = d.reference.copy(sha256 = "0".repeat(64))) }
            .isInstanceOf(CaseDocumentaryBundleUnavailable::class.java)
        val foreign = UUID.randomUUID()
        val foreignCase = d.commands.open(OpenCaseCommand(foreign, "Other", "Locked")).caseId
        assertThatThrownBy { d.attachments.pin(PinCaseDocumentaryBundleCommand(foreign, foreignCase, 1, d.reference)) }
            .isInstanceOf(CaseDocumentaryBundleUnavailable::class.java)
        assertThat(d.events.load(d.tenant, CaseId(case))).hasSize(4)
        assertDirectRunDenied(d, case, 4, "WAITING_FOR_DOCUMENTARY_EVIDENCE")
        d.attach(case)
        assertThatThrownBy { d.attach(case, 5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(d.events.load(d.tenant, CaseId(case))).hasSize(5)
    }

    @Test
    fun `concurrent same version attachments admit one event and projection`() {
        val d = CaseDocumentaryDatabase(postgres)
        val case = d.openPreparedCase()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls =
                (1..2).map {
                    pool.submit<Boolean> {
                        try {
                            d.attach(case)
                            true
                        } catch (_: ConcurrentCaseModificationException) {
                            false
                        }
                    }
                }
            assertThat(calls.map { it.get(10, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
            assertThat(d.pins.find(d.tenant, CaseId(case), 5)).hasSize(1)
            assertThat(d.events.load(d.tenant, CaseId(case))).hasSize(5)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `upgrade retains published contract and bundle then accepts the new event projection`() {
        val d = CaseDocumentaryDatabase(postgres, target = "20261030100000")
        val before = d.bundle.sha256
        val migrations =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(d.b.db.schema)
                .defaultSchema(d.b.db.schema)
                .load()
        migrations.migrate()
        migrations.validate()
        val case = d.openPreparedCase()
        d.attach(case)
        assertThat(
            d.b.repository
                .find(d.tenant, d.reference.bundleId)
                ?.sha256,
        ).isEqualTo(before)
        assertThat(d.readiness.evaluate(d.tenant.value, case)).isInstanceOf(CaseReadiness.Evaluated::class.java)
        val schema = "documentary_empty_" + UUID.randomUUID().toString().replace("-", "")
        val empty =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
        empty.migrate()
        empty.validate()
        assertThat(
            empty
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo("20261030130000")
    }

    private fun assertDirectRunDenied(
        d: CaseDocumentaryDatabase,
        case: UUID,
        version: Long,
        status: String,
    ) {
        val step = d.contract.steps.single()
        val run =
            ResolutionRunStart.create(
                ResolutionRunId(UUID.randomUUID()),
                CaseId(case),
                ResolutionRunPlan(
                    version,
                    ResolutionContractIdentity(d.contract.key, d.contract.revision),
                    d.policy.revision,
                    step.id,
                    step.capability,
                    StepPolicyDecision.Requirements(step.risk, step.approval),
                ),
            )
        assertThatThrownBy { d.transactions.required { d.runs.create(d.tenant, run) } }
            .isInstanceOfSatisfying(ResolutionRunNotReadyException::class.java) {
                assertThat(it.readiness).isEqualTo(status)
            }
        assertThat(d.runs.find(d.tenant, run.id)).isNull()
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
