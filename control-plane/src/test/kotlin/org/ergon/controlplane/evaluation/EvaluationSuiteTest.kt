package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationSuiteDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.GateDocumentJsonCodec
import org.ergon.controlplane.evaluation.adapter.inbound.document.GatePolicyDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.RequiredScenarioDocument
import org.ergon.controlplane.evaluation.adapter.inbound.http.EvaluationSuiteController
import org.ergon.controlplane.evaluation.application.EligibilityRequest
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationCommand
import org.ergon.controlplane.evaluation.application.EvaluationReportRegistration
import org.ergon.controlplane.evaluation.application.EvaluationReportRepository
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentConflict
import org.ergon.controlplane.evaluation.application.GateDocumentIdentity
import org.ergon.controlplane.evaluation.application.GateDocumentKind
import org.ergon.controlplane.evaluation.application.GateDocumentRegistration
import org.ergon.controlplane.evaluation.application.GateDocumentRepository
import org.ergon.controlplane.evaluation.application.GateDocumentUnavailable
import org.ergon.controlplane.evaluation.application.InvalidGateDocument
import org.ergon.controlplane.evaluation.application.PreparedEvaluationReport
import org.ergon.controlplane.evaluation.application.PreparedGateDocument
import org.ergon.controlplane.evaluation.application.PreparedSimulationFixture
import org.ergon.controlplane.evaluation.application.PublishedEvaluationContract
import org.ergon.controlplane.evaluation.application.SelectedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureIdentity
import org.ergon.controlplane.evaluation.application.SimulationFixtureRegistration
import org.ergon.controlplane.evaluation.application.SimulationFixtureRepository
import org.ergon.controlplane.evaluation.application.StoredEvaluationReport
import org.ergon.controlplane.evaluation.application.StoredGateDocument
import org.ergon.controlplane.evaluation.application.StoredSimulationFixture
import org.ergon.evaluation.domain.EligibilityReason
import org.ergon.evaluation.domain.SimulationArtifact
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class EvaluationSuiteTest {
    private val h = SuiteHarness()

    @Test
    fun `strict normalized documents reject empty sets duplicates unknown fields and forged hashes`() {
        val policy = h.codec.decode(GateDocumentKind.POLICY, h.policyBytes())
        val decoded = h.codec.decode(GateDocumentKind.POLICY, policy.definition.toByteArray())
        assertThat(decoded.digest).isEqualTo(policy.digest)
        val bad = h.policyBytes().toString(Charsets.UTF_8).dropLast(1) + ",\"passed\":true}"
        assertThatThrownBy { h.codec.decode(GateDocumentKind.POLICY, bad.toByteArray()) }
            .isInstanceOf(InvalidGateDocument::class.java)
        assertThatThrownBy { h.codec.decode(GateDocumentKind.SUITE, h.suiteBytes(emptyList())) }
            .isInstanceOf(InvalidGateDocument::class.java)
        val nullEvaluator =
            h
                .policyBytes()
                .toString(Charsets.UTF_8)
                .replace(Regex("\\[.*?]"), "[null]")
        assertThatThrownBy { h.codec.decode(GateDocumentKind.POLICY, nullEvaluator.toByteArray()) }
            .isInstanceOf(InvalidGateDocument::class.java)
        val members = listOf(h.member(), h.member())
        assertThatThrownBy { h.codec.decode(GateDocumentKind.SUITE, h.suiteBytes(members)) }
            .isInstanceOf(InvalidGateDocument::class.java)
    }

    @Test
    fun `gate consumes exact stored reports and refuses cherry picked or changed evidence`() {
        h.register()
        val selected = h.selection()
        assertThat(
            h.gate
                .assess(h.suiteId, h.request(selected))
                .result.eligible,
        ).isTrue()
        val missing = h.gate.assess(h.suiteId, h.request()).result
        assertThat(missing.scenarios.single().reasons).containsExactly(EligibilityReason.MISSING_REPORT)
        val wrongDigest = h.gate.assess(h.suiteId, h.request(selected.copy(reportDigest = "0".repeat(64)))).result
        assertThat(wrongDigest.scenarios.single().reasons).containsExactly(EligibilityReason.REPORT_IDENTITY_MISMATCH)
        val wrongId = h.gate.assess(h.suiteId, h.request(selected.copy(reportId = UUID.randomUUID()))).result
        assertThat(wrongId.eligible).isFalse()
        val altered = selected.copy(fixture = selected.fixture.copy(sha256 = "0".repeat(64)))
        val result = h.gate.assess(h.suiteId, h.request(altered)).result
        assertThat(result.reasons).containsExactly(EligibilityReason.UNEXPECTED_REPORT)
        assertThat(result.eligible).isFalse()
    }

    @Test
    fun `registration rejects foreign fixtures wrong contract and unknown required assertions`() {
        h.registerPolicy()
        val foreign = h.suiteId.copy(tenant = UUID.randomUUID())
        assertThatThrownBy { h.suites.register(GateDocumentKind.SUITE, foreign, h.suiteBytes(), h.actor) }
            .isInstanceOf(GateDocumentUnavailable::class.java)
        h.contractMatches = false
        assertThatThrownBy { h.suites.register(GateDocumentKind.SUITE, h.suiteId, h.suiteBytes(), h.actor) }
            .isInstanceOf(GateDocumentUnavailable::class.java)
        h.contractMatches = true
        val wrong = listOf(h.member().copy(requiredAssertions = listOf("missing-check")))
        assertThatThrownBy { h.suites.register(GateDocumentKind.SUITE, h.suiteId, h.suiteBytes(wrong), h.actor) }
            .isInstanceOf(InvalidGateDocument::class.java)
    }

    @Test
    fun `policy required budget cannot be satisfied by a passing unbudgeted report`() {
        h.requireCost = true
        h.register()
        val assessment = h.gate.assess(h.suiteId, h.request(h.selection()))
        assertThat(assessment.result.eligible).isFalse()
        assertThat(
            assessment.result.scenarios
                .single()
                .reasons,
        ).containsExactly(EligibilityReason.REQUIRED_COST_NOT_PASSED)
    }

    @Test
    fun `internal registration read and read only eligibility enforce strict bodies and tenant scope`() {
        val mvc = MockMvcBuilders.standaloneSetup(EvaluationSuiteController(h.suites, h.gate, h.f.pinned)).build()
        val base = "/internal/v1/tenants/${h.tenant}/evaluation-gates"
        mvc
            .perform(
                post("$base/POLICY/required/revisions/1").header("X-Ergon-Actor-Id", h.actor).content(h.policyBytes()),
            ).andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc
            .perform(
                post("$base/SUITE/regression/revisions/1").header("X-Ergon-Actor-Id", h.actor).content(h.suiteBytes()),
            ).andExpect(status().isCreated)
        mvc.perform(get("$base/SUITE/regression/revisions/1")).andExpect(status().isOk)
        val request =
            mapOf(
                "schemaVersion" to "ergon.simulation/eligibility-request/v1",
                "contract" to h.fixture.fixture.contract,
                "policy" to h.policyRef(),
                "reports" to listOf(h.selection()),
            )
        val body = h.f.mapper.writeValueAsBytes(request)
        mvc
            .perform(post("$base/SUITE/regression/revisions/1/eligibility").content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result.eligible").value(true))
        mvc
            .perform(post("$base/SUITE/regression/revisions/1/eligibility").content("{\"passed\":true}"))
            .andExpect(status().isUnprocessableEntity)
        mvc
            .perform(get("/internal/v1/tenants/${UUID.randomUUID()}/evaluation-gates/SUITE/regression/revisions/1"))
            .andExpect(status().isNotFound)
    }
}

internal class SuiteHarness {
    val f = EvaluationReportFixtures()
    val tenant = f.f.tenant
    val actor = UUID.randomUUID()
    val codec = GateDocumentJsonCodec(f.pinned, f.mapper)
    val fixtureId = SimulationFixtureIdentity(tenant, "sample", "1")
    val fixture = f.packages.decode(f.bytes())
    val fixtureRef = SimulationArtifact("sample", "1", fixture.fixture.semanticDigest())
    val policyId = GateDocumentIdentity(tenant, "required", "1")
    val suiteId = GateDocumentIdentity(tenant, "regression", "1")
    val reportId = UUID.randomUUID()
    val preparedReport = f.runner.execute(f.bytes())
    var contractMatches = true
    var requireCost = false
    val stored = mutableMapOf<Pair<GateDocumentKind, GateDocumentIdentity>, StoredGateDocument>()
    val documents =
        object : GateDocumentRepository {
            override fun register(
                identity: GateDocumentIdentity,
                document: PreparedGateDocument,
                actor: UUID,
            ): GateDocumentRegistration {
                val key = document.kind to identity
                val existing = stored[key]
                if (existing != null && existing.definition != document.definition) throw GateDocumentConflict()
                val result =
                    existing ?: StoredGateDocument(
                        identity,
                        document.kind,
                        document.digest,
                        document.definition,
                        actor,
                        Instant.EPOCH,
                    )
                stored[key] = result
                return GateDocumentRegistration(result, existing == null)
            }

            override fun find(
                kind: GateDocumentKind,
                identity: GateDocumentIdentity,
            ) = stored[kind to identity]
        }
    val fixtures =
        object : SimulationFixtureRepository {
            override fun register(
                identity: SimulationFixtureIdentity,
                prepared: PreparedSimulationFixture,
                actor: UUID,
            ): SimulationFixtureRegistration = error("unused")

            override fun find(identity: SimulationFixtureIdentity): StoredSimulationFixture? =
                if (identity ==
                    fixtureId
                ) {
                    StoredSimulationFixture(identity, fixtureRef.sha256, fixture.definition, actor, Instant.EPOCH)
                } else {
                    null
                }
        }
    val reports =
        object : EvaluationReportRepository {
            override fun record(
                command: EvaluationCommand,
                report: PreparedEvaluationReport,
                actor: UUID,
            ): EvaluationReportRegistration = error("unused")

            override fun find(
                tenant: UUID,
                id: UUID,
            ): StoredEvaluationReport? =
                if (tenant == this@SuiteHarness.tenant && id == reportId) {
                    StoredEvaluationReport(
                        EvaluationCommand(fixtureId, id),
                        fixtureRef.sha256,
                        preparedReport.digest,
                        preparedReport.definition,
                        preparedReport.content.passed,
                        actor,
                        Instant.EPOCH,
                    )
                } else {
                    null
                }
        }
    val suites =
        EvaluationSuites(
            documents,
            codec,
            fixtures,
            f.decoder,
            PublishedEvaluationContract { _, _ -> contractMatches },
        )
    val gate = EvaluatePromotionEligibility(suites, codec, reports, f.codec, fixtures, f.packages)

    fun policyBytes(): ByteArray =
        f.mapper.writeValueAsBytes(
            GatePolicyDocument(
                GateDocumentJsonCodec.POLICY_SCHEMA,
                listOf(fixture.fixture.evaluator),
                requireCost,
                false,
            ),
        )

    fun policyRef() = SimulationArtifact("required", "1", codec.decode(GateDocumentKind.POLICY, policyBytes()).digest)

    fun member() = RequiredScenarioDocument(fixtureRef, listOf("not-ready"))

    fun suiteBytes(members: List<RequiredScenarioDocument> = listOf(member())): ByteArray =
        f.mapper.writeValueAsBytes(
            EvaluationSuiteDocument(GateDocumentJsonCodec.SUITE_SCHEMA, fixture.fixture.contract, policyRef(), members),
        )

    fun registerPolicy() = suites.register(GateDocumentKind.POLICY, policyId, policyBytes(), actor)

    fun register() {
        registerPolicy()
        suites.register(GateDocumentKind.SUITE, suiteId, suiteBytes(), actor)
    }

    fun selection() = SelectedEvaluationReport(fixtureRef, reportId, preparedReport.digest)

    fun request(vararg selected: SelectedEvaluationReport): EligibilityRequest =
        EligibilityRequest(fixture.fixture.contract, policyRef(), selected.toList())
}
