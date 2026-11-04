package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.ErgonCase
import org.ergon.controlplane.cases.adapter.out.persistence.CaseEventJsonCodec
import org.ergon.controlplane.cases.adapter.out.persistence.PostgresCaseEventStore
import org.ergon.controlplane.cases.adapter.out.persistence.PostgresCaseProjectionWriter
import org.ergon.controlplane.cases.application.NewCaseEvent
import org.ergon.controlplane.contracts.ContractPromotionDatabase
import org.ergon.controlplane.contracts.adapter.out.persistence.PostgresCurrentContractPromotion
import org.ergon.controlplane.contracts.application.PromotionConflict
import org.ergon.controlplane.contracts.application.PromotionIneligible
import org.ergon.controlplane.contracts.application.PromotionUnavailable
import org.ergon.controlplane.contracts.application.RequiredSuiteIntent
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.adapter.out.persistence.PostgresSimulationLabAuthority
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationLabPromotions
import org.ergon.controlplane.evaluation.application.ViewerAttestation
import org.ergon.controlplane.resolution.RunPromotionConfiguration
import org.ergon.controlplane.resolution.adapter.out.persistence.PostgresResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.controlplane.resolution.application.ResolutionRunPromotionRequiredException
import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.ergon.evaluation.domain.AssertionStatus
import org.ergon.evaluation.domain.EligibilityReason
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class SimulationPromotionAcceptanceIntegrationTest {
    @Test
    fun `reproducible reports govern attributed promotion and durable new run admission`() {
        val harness = SuiteHarness({ it.bytes(acceptanceFailure(it)) }, "safe-policy", costRequired = true)
        val db = ContractPromotionDatabase(postgres, h = harness)
        val h = db.h
        val grant = db.grant()
        db.bind()
        val lab = lab(db)
        val run = readyRun(db)
        assertThatThrownBy { run.start() }.isInstanceOf(ResolutionRunPromotionRequiredException::class.java)

        val command = EvaluationCommand(h.fixtureId, h.reportId)
        val first = db.execution.get(db.tenant, command.id)
        assertThat(first.submittedBy).isEqualTo(db.actor)
        val retryActor = db.actor(db.tenant)
        val replay = db.execution.execute(command, retryActor)
        assertThat(replay.created).isFalse()
        assertThat(replay.stored).isEqualTo(first)
        val fresh = db.execution.execute(command.copy(id = UUID.randomUUID()), retryActor).stored
        assertThat(fresh.reportDigest).isEqualTo(first.reportDigest)
        assertThat(fresh.definition).isEqualTo(first.definition)
        val content =
            h.f.codec
                .decode(first.definition)
                .content
        assertThat(content.passed).isTrue()
        assertThat(content.observed.single().values["status"]).isEqualTo("ACTION_FAILED")
        assertThat(content.checks.single { it.id == "proof" }.status).isEqualTo(AssertionStatus.FAIL)
        assertThat(content.budgets?.cost?.measured).isEqualTo(10L)

        val eligible = lab.review(db.tenant, db.actor, db.key, listOf(h.selection()), "Review exact evidence")
        assertThat(eligible.eligibility.eligible).isTrue()
        rejectRequiredEvidence(db, lab, run)
        val current = lab.review(db.tenant, db.actor, db.key, listOf(h.selection()), "Review exact evidence")
        assertThat(current.eligibility.eligible).isTrue()
        assertThatThrownBy { lab.confirm(db.tenant, db.actor, UUID.randomUUID(), eligible.intent) }
            .isInstanceOf(PromotionConflict::class.java)

        val promotionId = UUID.randomUUID()
        val acknowledgement = lab.confirm(db.tenant, db.actor, promotionId, current.intent)
        val receipt = db.service.get(db.tenant, promotionId)
        assertThat(receipt.intent).isEqualTo(current.intent)
        assertThat(receipt.actorId).isEqualTo(db.actor)
        assertThat(receipt.authorityId).isEqualTo(grant.id)
        assertThat(lab.confirm(db.tenant, db.actor, promotionId, current.intent))
            .isEqualTo(acknowledgement.copy(created = false))
        val admitted = run.start()
        assertThat(admitted.run.initialState).isEqualTo(ResolutionRunInitialState.WAITING_FOR_APPROVAL)
        assertThat(run.service.get(db.tenant, admitted.run.id.value)).isEqualTo(admitted)

        bind(db, RequiredSuiteIntent(3, db.suite, "Renew binding"))
        assertThatThrownBy { readyRun(db).start() }.isInstanceOf(ResolutionRunPromotionRequiredException::class.java)
        assertThat(run.service.get(db.tenant, admitted.run.id.value)).isEqualTo(admitted)
        val renewed = lab.review(db.tenant, db.actor, db.key, listOf(h.selection()), "Review renewed binding")
        assertThat(renewed.intent.expectedVersion).isEqualTo(4L)
        lab.confirm(db.tenant, db.actor, UUID.randomUUID(), renewed.intent)
        assertThat(readyRun(db).start().run.contract).isEqualTo(admitted.run.contract)
    }

    private fun rejectRequiredEvidence(
        db: ContractPromotionDatabase,
        lab: SimulationLabPromotions,
        run: ReadyRun,
    ) {
        val h = db.h
        val selected = register(db, "over-budget", acceptanceFailure(h.f, limit = 9))
        val suite =
            db.suites
                .register(
                    GateDocumentKind.SUITE,
                    h.suiteId.copy(revision = "2"),
                    h.suiteBytes(
                        listOf(
                            h.member().copy(requiredAssertions = listOf("proof")),
                            h.member().copy(fixture = selected.fixture),
                        ),
                    ),
                    db.actor,
                ).stored
                .reference()
        bind(db, RequiredSuiteIntent(1, suite, "Review proof and budget gate"))
        val reports = listOf(selected, h.selection())
        val review = lab.review(db.tenant, db.actor, db.key, reports, "Review required evidence")
        assertThat(review.eligibility.eligible).isFalse()
        val reasons = review.eligibility.scenarios.associate { it.fixture to it.reasons }
        assertThat(reasons.getValue(selected.fixture)).contains(EligibilityReason.REQUIRED_COST_NOT_PASSED)
        assertThat(reasons.getValue(h.fixtureRef)).containsExactly(EligibilityReason.REQUIRED_ASSERTION_NOT_PASSED)
        val id = UUID.randomUUID()
        assertThatThrownBy { lab.confirm(db.tenant, db.actor, id, review.intent) }
            .isInstanceOf(PromotionIneligible::class.java)
        assertThatThrownBy { db.service.get(db.tenant, id) }.isInstanceOf(PromotionUnavailable::class.java)
        assertThatThrownBy { run.start() }.isInstanceOf(ResolutionRunPromotionRequiredException::class.java)
        bind(db, RequiredSuiteIntent(2, db.suite, "Restore passing suite"))
    }

    private fun bind(
        db: ContractPromotionDatabase,
        intent: RequiredSuiteIntent,
    ) {
        db.service.bind(db.tenant, db.key, UUID.randomUUID(), db.actor, intent)
    }

    @Test
    fun `persisted replay distinguishes documentary cutoff and uncertain connector outcomes`() {
        val db = ContractPromotionDatabase(postgres)
        val f = db.h.f
        val uncertain = register(db, "uncertain", acceptanceFailure(f, outcome = "UNKNOWN"))
        val report = f.codec.decode(db.execution.get(db.tenant, uncertain.reportId).definition).content
        assertThat(report.passed).isFalse()
        assertThat(report.observed.single().values["status"]).isEqualTo("INVOCATION_UNKNOWN")
        assertThat(report.budgets?.cost?.measured).isEqualTo(10L)

        val valid = register(db, "recorded-cutoff", documentaryScenario(f, valid = true))
        val expired = register(db, "expired-cutoff", documentaryScenario(f, valid = false))
        assertThat(db.execution.get(db.tenant, valid.reportId).passed).isTrue()
        assertThat(db.execution.get(db.tenant, expired.reportId).passed).isFalse()
    }

    private fun register(
        db: ContractPromotionDatabase,
        key: String,
        document: SimulationPackageDocument,
    ): SelectedEvaluationReport {
        val identity = SimulationFixtureIdentity(db.tenant, key, "1")
        val prepared =
            db.h.f.packages
                .decode(db.h.f.bytes(document))
        db.fixtures.register(identity, prepared, db.actor)
        val report = db.execution.execute(EvaluationCommand(identity, UUID.randomUUID()), db.actor).stored
        val fixture = SimulationArtifact(key, "1", report.fixtureDigest)
        return SelectedEvaluationReport(fixture, report.command.id, report.reportDigest)
    }

    private fun lab(db: ContractPromotionDatabase): SimulationLabPromotions {
        val authority = PostgresSimulationLabAuthority(db.jdbc, db.transactions)
        authority.attest(
            db.tenant,
            UUID.randomUUID(),
            ViewerAttestation(db.actor, "acceptance", "viewer", db.now().plusHours(1).toInstant()),
        )
        return SimulationLabPromotions(authority, db.runner, db.service, db.suites, db.h.codec, db.eligibility)
    }

    private fun readyRun(db: ContractPromotionDatabase): ReadyRun {
        val f = PinnedScenarioFixtures()
        val history = f.history()
        val tenant = TenantId(db.tenant)
        val caseId = CaseId(f.case)
        val events = PostgresCaseEventStore(db.jdbc, CaseEventJsonCodec(f.mapper))
        db.runner.required {
            val stored = events.append(tenant, caseId, 0, history.map { NewCaseEvent(UUID.randomUUID(), it) })
            PostgresCaseProjectionWriter(db.jdbc).project(ErgonCase.rehydrate(caseId, tenant, history), stored)
        }
        val readiness = ResolutionReadinessService(events, db.contracts) { _, _, _ -> emptyList() }
        val policy =
            db.h.f.decoder
                .decode(db.h.bytes)
                .pinned.policy
        val admission =
            RunPromotionConfiguration().runPromotionAdmission(
                PostgresCurrentContractPromotion(db.jdbc),
                db.tenant.toString(),
            )
        val service =
            ResolutionRunService(
                ResolutionPlanningService(readiness, policy),
                PostgresResolutionRunRepository(db.jdbc),
                { ResolutionRunId(UUID.randomUUID()) },
                db.runner,
                admission,
            )
        return ReadyRun(service, db.tenant, f.case, history.size.toLong())
    }

    private data class ReadyRun(
        val service: ResolutionRunService,
        val tenant: UUID,
        val case: UUID,
        val version: Long,
    ) {
        fun start() = service.start(tenant, case, version)
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }
}
