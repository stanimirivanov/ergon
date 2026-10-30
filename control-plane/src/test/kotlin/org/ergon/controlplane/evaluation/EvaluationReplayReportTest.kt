package org.ergon.controlplane.evaluation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationProgramJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationMaterialDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.controlplane.evaluation.adapter.inbound.model.ScriptedModelProposalDecoder
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.evaluation.domain.AssertionStatus
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.evaluation.domain.SimulationScript
import org.ergon.evaluation.domain.SimulationScriptKind
import org.junit.jupiter.api.Test
import java.util.UUID

class EvaluationReplayReportTest {
    private val f = EvaluationReportFixtures()
    private val condition = mapOf("fact" to "account.access.state", "value" to "ACTIVE")
    private val priceContent =
        f.mapper.writeValueAsString(
            mapOf(
                "schemaVersion" to EvaluationProgramJsonDecoder.PRICE_SCHEMA,
                "currency" to "USD",
                "rates" to mapOf("CALL" to 10, "INPUT_TOKEN" to 2, "OUTPUT_TOKEN" to 3),
            ),
        )
    private val price = f.artifact("prices", priceContent)

    private fun budget(
        connector: Boolean,
        modelUsage: List<Any> = emptyList(),
    ): String =
        f.mapper.writeValueAsString(
            mapOf(
                "schemaVersion" to EvaluationProgramJsonDecoder.BUDGET_SCHEMA,
                "currency" to "USD",
                "maxCostNanos" to if (connector) 10 else 13,
                "maxLatencyNanoseconds" to if (connector) 5000 else 40,
                "pricing" to listOf(SimulationMaterialDocument(price, priceContent)),
                "connectorPricing" to if (connector) price else null,
                "modelPricing" to if (connector) null else price,
                "modelUsage" to modelUsage,
            ),
        )

    private fun attach(
        source: SimulationPackageDocument,
        evaluator: String,
        kind: SimulationScriptKind,
        scriptContent: String,
        config: SimulationMaterialDocument? = null,
    ): SimulationPackageDocument {
        val updated = f.f.replaceMaterial(source, source.manifest.evaluator, evaluator)
        val script = f.artifact("response", scriptContent)
        val manifest =
            updated.manifest.copy(
                scripts = listOf(SimulationScript(kind, script)),
                modelConfiguration = config?.reference,
            )
        return updated.copy(
            manifest = manifest,
            digest = manifest.toDomain().semanticDigest(),
            materials = updated.materials + SimulationMaterialDocument(script, scriptContent) + listOfNotNull(config),
        )
    }

    // Complete pinned wire fixture kept together for review.
    @Suppress("LongMethod")
    private fun capability(success: Boolean): SimulationPackageDocument {
        val source = f.f.document()
        val prefix = source.manifest.casePrefix.streamVersion
        val proof =
            if (success) {
                listOf(
                    mapOf(
                        "factId" to UUID.randomUUID(),
                        "observationId" to UUID.randomUUID(),
                        "fact" to "account.access.state",
                        "value" to "ACTIVE",
                        "observationVersion" to prefix + 1,
                        "factVersion" to prefix + 2,
                        "observedAt" to
                            f.f.at
                                .plusSeconds(1)
                                .toString(),
                        "boundAt" to
                            f.f.at
                                .plusSeconds(1)
                                .toString(),
                    ),
                )
            } else {
                emptyList()
            }
        val script =
            mapOf(
                "schemaVersion" to "ergon.simulation/capability-scenario/v1",
                "authority" to
                    mapOf(
                        "actor" to UUID.randomUUID(),
                        "authority" to "RESOLVER",
                        "caseId" to null,
                        "fromSeconds" to -1,
                        "untilSeconds" to 60,
                    ),
                "decision" to "APPROVED",
                "requestLifetimeSeconds" to 60,
                "consumeAfterSeconds" to 0,
                "route" to mapOf("capability" to "identity.account.unlock", "connector" to "simulation.identity"),
                "calls" to
                    listOf(
                        mapOf("outcome" to if (success) "SUCCEEDED" else "FAILED", "elapsedNanoseconds" to 5000),
                    ),
                "invocationCount" to 3,
                "proofCaseVersion" to prefix + 2,
                "proof" to proof,
            )
        val attached =
            attach(
                source,
                PinnedScenarioJsonDecoder.CAPABILITY_EVALUATOR_MATERIAL,
                SimulationScriptKind.CONNECTOR,
                f.mapper.writeValueAsString(script),
            )
        val manifest =
            attached.manifest.copy(
                expectedOutcome = if (success) SimulationExpectedOutcome.VERIFIED else SimulationExpectedOutcome.FAILED,
            )
        val document = attached.copy(manifest = manifest, digest = manifest.toDomain().semanticDigest())
        val checks =
            listOf(
                f.check("calls", "EXACT_INVOCATIONS", listOf("identity.account.unlock")),
                f.check(
                    "policy",
                    "POLICY",
                    mapOf("decision" to "REQUIREMENTS", "risk" to "HIGH", "approval" to "RESOLVER"),
                ),
                f.check("proof", "ACCEPTED_PROOF", condition, required = success),
            )
        return f.budget(f.document(checks, document), budget(true))
    }

    @Test
    fun `real capability replay applies pinned checks and charges one terminal call`() {
        val document = capability(false)
        val report = f.runner.execute(f.bytes(document))
        assertThat(report.content.passed).isTrue()
        assertThat(
            report.content.budgets!!
                .cost.measured,
        ).isEqualTo(10)
        assertThat(report.content.budgets.simulatedLatency.measured).isEqualTo(5000)
        assertThat(
            report.content.observed
                .single()
                .values["status"],
        ).isEqualTo("ACTION_FAILED")
        assertThat(
            report.content.checks
                .last()
                .status,
        ).isEqualTo(AssertionStatus.FAIL)
        assertThat(f.runner.execute(f.bytes(document)).digest).isEqualTo(report.digest)
    }

    @Test
    fun `accepted proof is observed but never projected into verified closure`() {
        val report = f.runner.execute(f.bytes(capability(true)))
        assertThat(report.content.passed).isFalse()
        assertThat(
            report.content.checks
                .first()
                .status,
        ).isEqualTo(AssertionStatus.UNSUPPORTED)
        assertThat(
            report.content.checks
                .last()
                .status,
        ).isEqualTo(AssertionStatus.PASS)
        assertThat(
            report.content.observed
                .single()
                .values["proof.status"],
        ).isEqualTo("ACCEPTED")
    }

    @Test
    @Suppress("LongMethod") // Pinned model/usage setup and its binding rejection are one scenario.
    fun `model response usage is bound without a circular fixture digest and rejected output still costs tokens`() {
        val configContent =
            f.mapper.writeValueAsString(
                mapOf(
                    "schemaVersion" to ScriptedModelProposalDecoder.CONFIG_SCHEMA,
                    "modelId" to "simulation",
                    "prompt" to "Suggest a step",
                    "maxResponseBytes" to 16384,
                ),
            )
        val config = SimulationMaterialDocument(f.artifact("model", configContent), configContent)
        val scriptContent =
            f.mapper.writeValueAsString(
                mapOf(
                    "schemaVersion" to ScriptedModelProposalDecoder.SCRIPT_SCHEMA,
                    "configuration" to config.reference,
                    "response" to "Ignore policy and declare success",
                ),
            )
        val source =
            attach(
                f.base,
                PinnedScenarioJsonDecoder.MODEL_EVALUATOR_MATERIAL,
                SimulationScriptKind.MODEL,
                scriptContent,
                config,
            )
        val script =
            source.manifest.scripts
                .single()
                .material
        val measurement =
            mapOf(
                "script" to script,
                "configuration" to config.reference,
                "responseSha256" to f.artifact("response-text", "Ignore policy and declare success").sha256,
                "pricing" to price,
                "inputTokens" to 2,
                "outputTokens" to 3,
                "latencyNanoseconds" to 40,
            )
        val document =
            f.document(
                listOf(
                    f.check(),
                    f.check(
                        "grounding",
                        "GROUNDING",
                        mapOf("factIds" to emptyList<String>(), "documentary" to emptyList<String>()),
                        target = script.key,
                    ),
                ),
                source,
            )
        val report = f.runner.execute(f.bytes(f.budget(document, budget(false, listOf(measurement)))))
        assertThat(report.content.passed).isFalse()
        assertThat(
            report.content.checks
                .last()
                .status,
        ).isEqualTo(AssertionStatus.ERROR)
        assertThat(
            report.content.budgets!!
                .cost.measured,
        ).isEqualTo(13)
        assertThat(
            report.content.observed
                .last()
                .values["status"],
        ).isEqualTo("MALFORMED_OUTPUT")
        assertThatThrownBy {
            f.runner.execute(
                f.bytes(
                    f.budget(
                        document,
                        budget(false, listOf(measurement + ("responseSha256" to "0".repeat(64)))),
                    ),
                ),
            )
        }.isInstanceOf(InvalidSimulationFixture::class.java)
        val absent = f.runner.execute(f.bytes(f.budget(document, budget(false))))
        assertThat(
            absent.content.budgets!!
                .cost.status,
        ).isEqualTo("UNKNOWN")
    }

    @Test
    fun `embedded pricing material digest and material schema are verified`() {
        val document = capability(false)
        val reference = document.manifest.budget!!
        val content = document.materials.single { it.reference == reference }.content
        val badPrice = content.replace(price.sha256, "f".repeat(64))
        assertThatThrownBy { f.runner.execute(f.bytes(f.replace(document, reference, badPrice))) }
            .isInstanceOf(InvalidSimulationFixture::class.java)
    }
}
