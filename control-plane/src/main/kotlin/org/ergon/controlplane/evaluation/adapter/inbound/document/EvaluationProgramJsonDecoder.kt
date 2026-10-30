package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.DocumentaryBundleRequirement
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.evaluation.adapter.inbound.model.ScriptedModelProposalDecoder
import org.ergon.controlplane.evaluation.application.EvaluationBudgetProgram
import org.ergon.controlplane.evaluation.application.EvaluationProgram
import org.ergon.controlplane.evaluation.application.EvaluationProgramDecoder
import org.ergon.controlplane.evaluation.application.EvaluationProgramKind
import org.ergon.controlplane.evaluation.application.InvalidSimulationFixture
import org.ergon.controlplane.evaluation.application.ScriptedModelUsage
import org.ergon.controlplane.evaluation.application.TargetedAssertion
import org.ergon.controlplane.evaluation.application.UnsupportedPinnedScenario
import org.ergon.controlplane.evaluation.application.UnsupportedScenarioReason
import org.ergon.evaluation.domain.BudgetPricing
import org.ergon.evaluation.domain.BudgetUnit
import org.ergon.evaluation.domain.EvaluationBudget
import org.ergon.evaluation.domain.ScenarioAssertion
import org.ergon.evaluation.domain.ScenarioAssertions
import org.ergon.evaluation.domain.ScenarioCheck
import org.ergon.evaluation.domain.ScenarioGrounding
import org.ergon.evaluation.domain.SimulationArtifact
import org.ergon.evaluation.domain.SimulationExpectedOutcome
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.StepPolicyDecision
import org.ergon.resolution.domain.StepPolicyDenialReason
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.List.copyOf
import java.util.UUID

/** Decode verified complete packages; expected values are never sourced from replay results. */
@Component
class EvaluationProgramJsonDecoder(
    private val packages: SimulationFixtureJsonCodec,
    private val pinned: PinnedScenarioJsonDecoder,
    private val capability: CapabilityScenarioJsonDecoder,
    private val model: ScriptedModelProposalDecoder,
    private val mapper: JsonMapper,
) : EvaluationProgramDecoder {
    override fun decode(bytes: ByteArray): EvaluationProgram =
        try {
            decodeValidated(packages.decode(bytes).definition)
        } catch (_: JacksonException) {
            throw InvalidSimulationFixture()
        } catch (_: IllegalArgumentException) {
            throw InvalidSimulationFixture()
        }

    private fun decodeValidated(definition: String): EvaluationProgram {
        val document = mapper.readValue(definition, SimulationPackageDocument::class.java)
        val materials = document.materials.associate { it.reference to it.content }
        val fixture = document.manifest.toDomain()
        val bytes = definition.toByteArray(Charsets.UTF_8)
        val kind =
            when (materials.getValue(fixture.evaluator)) {
                PinnedScenarioJsonDecoder.EVALUATOR_MATERIAL -> EvaluationProgramKind.PLANNING
                PinnedScenarioJsonDecoder.CAPABILITY_EVALUATOR_MATERIAL -> EvaluationProgramKind.CAPABILITY
                PinnedScenarioJsonDecoder.MODEL_EVALUATOR_MATERIAL -> EvaluationProgramKind.MODEL
                else -> throw UnsupportedPinnedScenario(UnsupportedScenarioReason.EVALUATOR)
            }
        val connector = if (kind == EvaluationProgramKind.CAPABILITY) capability.decode(bytes) else null
        val proposals = if (kind == EvaluationProgramKind.MODEL) model.decode(bytes) else null
        require(proposals == null || fixture.scripts.none { it.material.key == "scenario" })
        val input = connector?.pinned ?: proposals?.pinned ?: pinned.decode(bytes)
        val assertionDocument =
            pinned.read(
                materials.getValue(fixture.assertions),
                EvaluationAssertionsDocument::class.java,
                ASSERTIONS_SCHEMA,
            )
        require(assertionDocument.checks.size in 1..MAX_CHECKS)
        val checks =
            assertionDocument.checks.map {
                requireNotNull(it)
                require(it.id != "fixture-outcome")
                require(it.target.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")))
                val expected = EvaluationAssertionJson.check(it.kind, it.expected)
                TargetedAssertion(it.target, ScenarioAssertion(it.id, it.required, expected))
            }
        ScenarioAssertions(checks.map { it.assertion })
        val budget =
            fixture.budget?.let {
                budget(materials.getValue(it), fixture.semanticDigest())
            }
        validateBudgetKind(kind, budget)
        validateModelUsage(budget, proposals)
        return EvaluationProgram(kind, input, connector, proposals, copyOf(checks), budget)
    }

    private fun validateBudgetKind(
        kind: EvaluationProgramKind,
        budget: EvaluationBudgetProgram?,
    ) {
        if (budget != null) {
            require(kind != EvaluationProgramKind.CAPABILITY || budget.connectorPricing != null)
            require(kind != EvaluationProgramKind.MODEL || budget.modelPricing != null)
            require(kind == EvaluationProgramKind.MODEL || budget.modelUsage.isEmpty())
        }
    }

    private fun validateModelUsage(
        budget: EvaluationBudgetProgram?,
        proposals: org.ergon.controlplane.evaluation.application.ModelProposalInput?,
    ) {
        if (budget != null && proposals != null) {
            require(
                budget.modelUsage
                    .map { it.script }
                    .distinct()
                    .size == budget.modelUsage.size,
            )
            val responses = proposals.responses.associateBy { it.script }
            budget.modelUsage.forEach {
                require(it.configuration == proposals.configuration)
                require(responses[it.script]?.responseSha256 == it.responseSha256)
                require(it.inputTokens == null || it.inputTokens >= 0)
                require(it.outputTokens == null || it.outputTokens >= 0)
                require(it.latencyNanoseconds == null || it.latencyNanoseconds >= 0)
            }
        }
    }

    private fun budget(
        content: String,
        fixtureDigest: String,
    ): EvaluationBudgetProgram {
        val document = pinned.read(content, EvaluationBudgetDocument::class.java, BUDGET_SCHEMA)
        require(document.pricing.size <= MAX_ITEMS && document.modelUsage.size <= MAX_ITEMS)
        val limits = EvaluationBudget(document.currency, document.maxCostNanos, document.maxLatencyNanoseconds)
        require(limits.maxCostNanos != null || limits.maxLatencyNanoseconds != null)
        val pricing =
            document.pricing.map {
                requireNotNull(it)
                require(sha256(it.content) == it.reference.sha256)
                val price = pinned.read(it.content, EvaluationPriceDocument::class.java, PRICE_SCHEMA)
                price.rates.values.forEach { rate -> requireNotNull(rate) }
                BudgetPricing(it.reference, price.currency, price.rates)
            }
        require(pricing.map { it.artifact }.distinct().size == pricing.size)
        val usage =
            document.modelUsage.map {
                requireNotNull(it)
                ScriptedModelUsage(
                    fixtureDigest,
                    it.script,
                    it.configuration,
                    it.responseSha256,
                    it.pricing,
                    it.inputTokens,
                    it.outputTokens,
                    it.latencyNanoseconds,
                )
            }
        return EvaluationBudgetProgram(
            limits,
            copyOf(pricing),
            document.connectorPricing,
            document.modelPricing,
            copyOf(usage),
        )
    }

    companion object {
        const val ASSERTIONS_SCHEMA = "ergon.simulation/assertions/v1"
        const val BUDGET_SCHEMA = "ergon.simulation/budget/v1"
        const val PRICE_SCHEMA = "ergon.simulation/pricing/v1"
        private const val MAX_CHECKS = 99
        private const val MAX_ITEMS = 100
    }
}

internal fun sha256(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

data class EvaluationAssertionsDocument(
    val schemaVersion: String,
    val checks: List<EvaluationCheckDocument>,
)

data class EvaluationCheckDocument(
    val id: String,
    val required: Boolean,
    val target: String,
    val kind: String,
    val expected: JsonNode,
)

data class EvaluationPriceDocument(
    val schemaVersion: String,
    val currency: String,
    val rates: Map<BudgetUnit, Long>,
)

data class EvaluationBudgetDocument(
    val schemaVersion: String,
    val currency: String,
    val maxCostNanos: Long?,
    val maxLatencyNanoseconds: Long?,
    val pricing: List<SimulationMaterialDocument>,
    val connectorPricing: SimulationArtifact?,
    val modelPricing: SimulationArtifact?,
    val modelUsage: List<EvaluationModelUsageDocument>,
)

data class EvaluationModelUsageDocument(
    val script: SimulationArtifact,
    val configuration: SimulationArtifact,
    val responseSha256: String,
    val pricing: SimulationArtifact,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val latencyNanoseconds: Long?,
)

/** Expected shapes are parsed independently of every observed replay value. */
internal object EvaluationAssertionJson {
    private const val MAX_ITEMS = 100

    fun check(
        kind: String,
        expected: JsonNode,
    ): ScenarioCheck =
        when (kind) {
            "OUTCOME" -> {
                ScenarioCheck.Outcome(SimulationExpectedOutcome.valueOf(text(expected)))
            }

            "ALLOWED_INVOCATIONS" -> {
                ScenarioCheck.AllowedInvocations(capabilities(expected).toSet())
            }

            "EXACT_INVOCATIONS" -> {
                ScenarioCheck.ExactInvocations(capabilities(expected))
            }

            "POLICY" -> {
                policy(expected)
            }

            "GROUNDING" -> {
                grounding(expected)
            }

            "ACCEPTED_PROOF" -> {
                ScenarioCheck.AcceptedProof(condition(expected))
            }

            "NO_UNPROVED_CLOSURE" -> {
                ScenarioCheck.NoUnprovedClosure(condition(expected))
            }

            "ESCALATION" -> {
                ScenarioCheck.Escalation(
                    if (expected.isNull) null else ResolutionRunEscalationReason.valueOf(text(expected)),
                )
            }

            else -> {
                ScenarioCheck.Unsupported(kind)
            }
        }

    private fun policy(node: JsonNode): ScenarioCheck.Policy {
        val decision = text(node.path("decision"))
        return if (decision == "DENIED") {
            fields(node, "decision", "reason")
            ScenarioCheck.Policy(StepPolicyDecision.Denied(StepPolicyDenialReason.valueOf(text(node.path("reason")))))
        } else {
            require(decision == "REQUIREMENTS")
            fields(node, "decision", "risk", "approval")
            ScenarioCheck.Policy(
                StepPolicyDecision.Requirements(
                    StepRisk.valueOf(text(node.path("risk"))),
                    ApprovalRequirement.valueOf(text(node.path("approval"))),
                ),
            )
        }
    }

    private fun grounding(node: JsonNode): ScenarioCheck.Grounding {
        fields(node, "factIds", "documentary")
        val facts = array(node.path("factIds")).map { UUID.fromString(text(it)) }
        val bundles =
            array(node.path("documentary")).map {
                fields(it, "bundleId", "sha256")
                DocumentaryBundleRequirement(UUID.fromString(text(it.path("bundleId"))), text(it.path("sha256")))
            }
        return ScenarioCheck.Grounding(ScenarioGrounding(facts, bundles))
    }

    private fun condition(node: JsonNode): FactCondition {
        fields(node, "fact", "value")
        return FactCondition(
            ContractFactType.of(text(node.path("fact"))),
            ContractFactValue.of(text(node.path("value"))),
        )
    }

    private fun fields(
        node: JsonNode,
        vararg names: String,
    ) {
        require(node.isObject && node.properties().map { it.key }.toSet() == names.toSet())
    }

    private fun text(node: JsonNode): String {
        require(node.isString)
        return node.asString()
    }

    private fun array(node: JsonNode): List<JsonNode> {
        require(node.isArray && node.size() <= MAX_ITEMS)
        return node.toList()
    }

    private fun capabilities(node: JsonNode) = array(node).map { CapabilityName.of(text(it)) }
}
