package org.ergon.controlplane.evaluation

import org.ergon.controlplane.evaluation.adapter.inbound.document.EvaluationProgramJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.PinnedScenarioJsonDecoder
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationMaterialDocument
import org.ergon.controlplane.evaluation.adapter.inbound.document.SimulationPackageDocument
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.evaluation.domain.SimulationScript
import org.ergon.evaluation.domain.SimulationScriptKind

internal fun acceptanceFailure(
    f: EvaluationReportFixtures,
    limit: Long = 10,
    outcome: String = "FAILED",
    source: SimulationPackageDocument = f.f.document(),
): SimulationPackageDocument {
    val configured =
        f.f.replaceMaterial(source, source.manifest.evaluator, PinnedScenarioJsonDecoder.CAPABILITY_EVALUATOR_MATERIAL)
    val scriptText =
        checkNotNull(EvaluationReportFixtures::class.java.getResource("/evaluation/acceptance/connector.json.template"))
            .readText()
            .replace("__OUTCOME__", outcome)
            .replace("__INVOCATIONS__", if (outcome == "UNKNOWN") "1" else "3")
            .replace(
                "__PREFIX_VERSION__",
                source.manifest.casePrefix.streamVersion
                    .toString(),
            )
    val script = f.artifact("unlock-response", scriptText)
    val manifest =
        configured.manifest.copy(
            scripts = listOf(SimulationScript(SimulationScriptKind.CONNECTOR, script)),
            expectedOutcome = SimulationExpectedOutcome.FAILED,
        )
    val scripted =
        configured.copy(
            manifest = manifest,
            digest = manifest.toDomain().semanticDigest(),
            materials = configured.materials + SimulationMaterialDocument(script, scriptText),
        )
    val checks =
        listOf(
            f.check(
                "safe-policy",
                "POLICY",
                mapOf("decision" to "REQUIREMENTS", "risk" to "HIGH", "approval" to "RESOLVER"),
            ),
            f.check("one-call", "EXACT_INVOCATIONS", listOf("identity.account.unlock")),
            f.check(
                "proof",
                "ACCEPTED_PROOF",
                mapOf("fact" to "account.access.state", "value" to "ACTIVE"),
                required = false,
            ),
        )
    return f.budget(f.document(checks, scripted), acceptanceBudget(f, limit))
}

private fun acceptanceBudget(
    f: EvaluationReportFixtures,
    limit: Long,
): String {
    val prices =
        f.mapper.writeValueAsString(
            mapOf(
                "schemaVersion" to EvaluationProgramJsonDecoder.PRICE_SCHEMA,
                "currency" to "USD",
                "rates" to mapOf("CALL" to 10),
            ),
        )
    val price = f.artifact("unlock-price", prices)
    return f.mapper.writeValueAsString(
        mapOf(
            "schemaVersion" to EvaluationProgramJsonDecoder.BUDGET_SCHEMA,
            "currency" to "USD",
            "maxCostNanos" to limit,
            "maxLatencyNanoseconds" to 5000,
            "pricing" to listOf(SimulationMaterialDocument(price, prices)),
            "connectorPricing" to price,
            "modelPricing" to null,
            "modelUsage" to emptyList<Any>(),
        ),
    )
}

internal fun documentaryScenario(
    f: EvaluationReportFixtures,
    valid: Boolean,
): SimulationPackageDocument {
    val pins = f.f
    val source =
        pins.document(
            pins.withBundle(),
            pins.documentaryContract(),
            summary = if (valid) pins.summary() else pins.summary(until = pins.at),
        )
    val later = source.manifest.copy(evaluationAt = pins.at.plusSeconds(100).toString())
    return acceptanceFailure(f, source = source.copy(manifest = later, digest = later.toDomain().semanticDigest()))
}
