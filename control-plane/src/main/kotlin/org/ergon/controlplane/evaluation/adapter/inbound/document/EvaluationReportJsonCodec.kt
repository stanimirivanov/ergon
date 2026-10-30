package org.ergon.controlplane.evaluation.adapter.inbound.document

import org.ergon.controlplane.evaluation.application.EvaluationBudgetDimension
import org.ergon.controlplane.evaluation.application.EvaluationReportCodec
import org.ergon.controlplane.evaluation.application.EvaluationReportContent
import org.ergon.controlplane.evaluation.application.EvaluationReportRunner
import org.ergon.controlplane.evaluation.application.PreparedEvaluationReport
import org.ergon.controlplane.evaluation.application.SimulationFixtureTooLarge
import org.ergon.evaluation.domain.AssertionStatus
import org.ergon.evaluation.domain.BudgetMeasurementReason
import org.ergon.evaluation.domain.BudgetStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Canonical compact UTF-8 JSON: recursive lexicographic object keys, ordered arrays, no recording metadata. */
@Component
class EvaluationReportJsonCodec(
    private val mapper: JsonMapper,
    private val strict: PinnedScenarioJsonDecoder,
) : EvaluationReportCodec {
    override fun encode(content: EvaluationReportContent): PreparedEvaluationReport {
        validate(content)
        val definition = canonical(mapper.readTree(mapper.writeValueAsString(content)))
        if (definition.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw SimulationFixtureTooLarge()
        return PreparedEvaluationReport(content, definition, sha256(definition))
    }

    override fun decode(definition: String): PreparedEvaluationReport {
        if (definition.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw SimulationFixtureTooLarge()
        val content = strict.read(definition, EvaluationReportContent::class.java, EvaluationReportRunner.REPORT_SCHEMA)
        val report = encode(content)
        check(report.definition == definition) { "stored report is not canonical" }
        return report
    }

    private fun canonical(node: JsonNode): String =
        when {
            node.isObject -> {
                node.properties().sortedBy { it.key }.joinToString(",", "{", "}") {
                    mapper.writeValueAsString(it.key) + ":" + canonical(it.value)
                }
            }

            node.isArray -> {
                node.joinToString(",", "[", "]") { canonical(it) }
            }

            else -> {
                node.toString()
            }
        }

    private fun validate(content: EvaluationReportContent) {
        require(content.schemaVersion == EvaluationReportRunner.REPORT_SCHEMA)
        require(content.fixtureDigest.matches(Regex("[0-9a-f]{64}")))
        require(content.checks.size in 2..MAX_CHECKS && content.observed.size in 1..MAX_CHECKS)
        content.checks.forEach { requireNotNull(it) }
        require(
            content.checks
                .map { it.id }
                .distinct()
                .size == content.checks.size,
        )
        require(content.checks.first().id == "fixture-outcome" && content.checks.first().required)
        require(
            content.observed
                .map { it.target }
                .distinct()
                .size == content.observed.size,
        )
        content.observed.forEach {
            requireNotNull(it)
            require(it.values.size <= MAX_FIELDS)
        }
        require((content.budget == null) == (content.budgets == null))
        val budgetPass =
            content.budgets?.let {
                require(it.currency.matches(Regex("[A-Z]{3}")))
                val dimensions = listOf(it.cost, it.simulatedLatency)
                dimensions.forEach(::validateDimension)
                require(dimensions.any { value -> value.limit != null })
                dimensions
                    .filter { value -> value.limit != null }
                    .all { value -> value.status == BudgetStatus.PASS.name }
            } ?: true
        val assertionsPass = content.checks.filter { it.required }.all { it.status == AssertionStatus.PASS }
        require(content.passed == (assertionsPass && budgetPass))
    }

    private fun validateDimension(value: EvaluationBudgetDimension) {
        val status = BudgetStatus.valueOf(value.status)
        require(value.limit == null || value.limit >= 0)
        require(value.measured == null || value.measured >= 0)
        require((status == BudgetStatus.NOT_CONFIGURED) == (value.limit == null))
        value.reason?.let { BudgetMeasurementReason.valueOf(it) }
        if (status == BudgetStatus.PASS || status == BudgetStatus.FAIL) {
            require(value.reason == null && value.measured != null)
            require((status == BudgetStatus.PASS) == (value.measured <= checkNotNull(value.limit)))
        } else if (status != BudgetStatus.NOT_CONFIGURED) {
            require(value.measured == null && value.reason != null)
        }
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        private const val MAX_CHECKS = 101
        private const val MAX_FIELDS = 1_024
    }
}
