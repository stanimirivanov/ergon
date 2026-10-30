package org.ergon.evaluation.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class EvaluationBudgetsTest {
    private val reference = SimulationArtifact("prices", "1", "a".repeat(64))
    private val pricing = BudgetPricing(reference, "USD", mapOf(BudgetUnit.CALL to 5L))
    private val attempt = BudgetAttempt("call-1", reference, mapOf(BudgetUnit.CALL to 2L), 20L)
    private val usage = ScenarioBudgetUsage(listOf(attempt))

    private fun evaluate(
        budget: EvaluationBudget = EvaluationBudget("USD", 10L, 20L),
        measured: ScenarioBudgetUsage? = usage,
        prices: List<BudgetPricing> = listOf(pricing),
    ): EvaluationBudgetResult = EvaluationBudgetEvaluator.evaluate(budget, measured, prices)

    @Test
    fun `inclusive limits use exact units with independent cost and simulated latency verdicts`() {
        val result = evaluate()
        assertThat(result.cost.measurement).isEqualTo(BudgetMeasurement.Known(10))
        assertThat(result.simulatedLatency.measurement).isEqualTo(BudgetMeasurement.Known(20))
        assertThat(result.configuredBudgetsPassed).isTrue()
        val exceeded = evaluate(EvaluationBudget("USD", 9, 19))
        assertThat(exceeded.cost.status).isEqualTo(BudgetStatus.FAIL)
        assertThat(exceeded.simulatedLatency.status).isEqualTo(BudgetStatus.FAIL)
        assertThat(exceeded.configuredBudgetsPassed).isFalse()
        assertThat(evaluate(EvaluationBudget("USD", null, 20)).configuredBudgetsPassed).isTrue()
        assertThat(evaluate(EvaluationBudget("USD", null, null)).configuredBudgetsPassed).isFalse()
    }

    @Test
    fun `missing observations differ from observed zero attempts and explicit free pricing`() {
        val absent = evaluate(measured = null)
        assertThat(absent.cost.status).isEqualTo(BudgetStatus.UNKNOWN)
        assertThat(absent.simulatedLatency.status).isEqualTo(BudgetStatus.UNKNOWN)
        assertThat(absent.configuredBudgetsPassed).isFalse()
        val zero = evaluate(EvaluationBudget("USD", 0, 0), ScenarioBudgetUsage(emptyList()), emptyList())
        assertThat(zero.configuredBudgetsPassed).isTrue()
        assertThat(zero.cost.measurement).isEqualTo(BudgetMeasurement.Known(0))
        val free = BudgetPricing(reference, "USD", mapOf(BudgetUnit.CALL to 0L))
        assertThat(evaluate(prices = listOf(free)).cost.measurement).isEqualTo(BudgetMeasurement.Known(0))
    }

    @Test
    fun `unknown usage duration pricing and rates never produce partial or zero totals`() {
        val unknown = BudgetAttempt("unknown", reference, mapOf(BudgetUnit.CALL to null), null)
        val result = evaluate(measured = ScenarioBudgetUsage(listOf(attempt, unknown)))
        assertThat(result.cost.measurement).isEqualTo(BudgetMeasurement.Unknown(BudgetMeasurementReason.MISSING_USAGE))
        assertThat(result.simulatedLatency.measurement)
            .isEqualTo(BudgetMeasurement.Unknown(BudgetMeasurementReason.MISSING_DURATION))
        assertThat(evaluate(prices = emptyList()).cost.measurement)
            .isEqualTo(BudgetMeasurement.Unknown(BudgetMeasurementReason.MISSING_PRICING))
        assertThat(evaluate(prices = listOf(BudgetPricing(reference, "USD", emptyMap()))).cost.status)
            .isEqualTo(BudgetStatus.UNKNOWN)
        val notConfigured = evaluate(EvaluationBudget("USD", null, null), null)
        assertThat(notConfigured.cost.status).isEqualTo(BudgetStatus.NOT_CONFIGURED)
        assertThat(notConfigured.cost.measurement).isInstanceOf(BudgetMeasurement.Unknown::class.java)
    }

    @Test
    fun `exact pricing revision and digest are required and currency is never converted`() {
        val otherRevision = BudgetPricing(reference.copy(revision = "2"), "USD", mapOf(BudgetUnit.CALL to 1L))
        val otherDigest = BudgetPricing(reference.copy(sha256 = "b".repeat(64)), "USD", mapOf(BudgetUnit.CALL to 1L))
        listOf(otherRevision, otherDigest).forEach { other ->
            assertThat(evaluate(prices = listOf(other)).cost.status).isEqualTo(BudgetStatus.UNKNOWN)
        }
        val currency = evaluate(prices = listOf(BudgetPricing(reference, "EUR", pricing.rates)))
        assertThat(currency.cost.measurement)
            .isEqualTo(BudgetMeasurement.Error(BudgetMeasurementReason.CURRENCY_MISMATCH))
        assertThat(currency.simulatedLatency.status).isEqualTo(BudgetStatus.PASS)
    }

    @Test
    fun `overflow in products or sums is an error even when other usage is unknown`() {
        val huge = BudgetPricing(reference, "USD", mapOf(BudgetUnit.CALL to Long.MAX_VALUE))
        assertThat(evaluate(prices = listOf(huge)).cost.measurement)
            .isEqualTo(BudgetMeasurement.Error(BudgetMeasurementReason.OVERFLOW))
        val first = BudgetAttempt("first", reference, mapOf(BudgetUnit.CALL to 1L), Long.MAX_VALUE)
        val second = BudgetAttempt("second", reference, mapOf(BudgetUnit.CALL to 1L), 1L)
        val unknown = BudgetAttempt("unknown", reference, mapOf(BudgetUnit.CALL to null), null)
        val total = evaluate(measured = ScenarioBudgetUsage(listOf(first, second, unknown)), prices = listOf(huge))
        assertThat(total.cost.status).isEqualTo(BudgetStatus.ERROR)
        assertThat(total.simulatedLatency.status).isEqualTo(BudgetStatus.ERROR)
        assertThat(total.configuredBudgetsPassed).isFalse()
    }

    @Test
    fun `input and output tokens use independent rates without rounding`() {
        val prices = BudgetPricing(reference, "USD", mapOf(BudgetUnit.INPUT_TOKEN to 3L, BudgetUnit.OUTPUT_TOKEN to 7L))
        val measured =
            ScenarioBudgetUsage(
                listOf(
                    BudgetAttempt(
                        "model",
                        reference,
                        mapOf(BudgetUnit.INPUT_TOKEN to 2L, BudgetUnit.OUTPUT_TOKEN to 4L),
                        0L,
                    ),
                ),
            )
        assertThat(evaluate(measured = measured, prices = listOf(prices)).cost.measurement)
            .isEqualTo(BudgetMeasurement.Known(34))
    }

    @Test
    fun `negative quantities duplicate identities and oversized inputs are rejected`() {
        assertThatIllegalArgumentException().isThrownBy { EvaluationBudget("usd", 1, 1) }
        assertThatIllegalArgumentException().isThrownBy { EvaluationBudget("USD", -1, 1) }
        assertThatIllegalArgumentException().isThrownBy {
            BudgetPricing(reference, "USD", mapOf(BudgetUnit.CALL to -1))
        }
        assertThatIllegalArgumentException().isThrownBy {
            BudgetAttempt("bad", reference, mapOf(BudgetUnit.CALL to -1L), 0)
        }
        assertThatIllegalArgumentException().isThrownBy { ScenarioBudgetUsage(listOf(attempt, attempt)) }
        assertThatIllegalArgumentException().isThrownBy { evaluate(prices = listOf(pricing, pricing)) }
        assertThatIllegalArgumentException().isThrownBy {
            ScenarioBudgetUsage(List(201) { BudgetAttempt("call-$it", reference, mapOf(BudgetUnit.CALL to 1L), 0) })
        }
    }

    @Test
    fun `unit map insertion order cannot change a missing measurement explanation`() {
        val first = linkedMapOf<BudgetUnit, Long?>(BudgetUnit.CALL to null, BudgetUnit.INPUT_TOKEN to 1L)
        val second = linkedMapOf<BudgetUnit, Long?>(BudgetUnit.INPUT_TOKEN to 1L, BudgetUnit.CALL to null)
        val results =
            listOf(first, second).map { units ->
                evaluate(measured = ScenarioBudgetUsage(listOf(BudgetAttempt("call", reference, units, 0))))
            }
        assertThat(results[0]).isEqualTo(results[1])
        assertThat(results[0].cost.measurement)
            .isEqualTo(BudgetMeasurement.Unknown(BudgetMeasurementReason.MISSING_USAGE))
    }

    @Test
    fun `caller mutation cannot change an accounting result`() {
        val units = mutableMapOf<BudgetUnit, Long?>(BudgetUnit.CALL to 2L)
        val rates = mutableMapOf(BudgetUnit.CALL to 5L)
        val calls = mutableListOf(BudgetAttempt("call", reference, units, 20))
        val usage = ScenarioBudgetUsage(calls)
        val prices = listOf(BudgetPricing(reference, "USD", rates))
        val first = evaluate(measured = usage, prices = prices)
        units.clear()
        rates.clear()
        calls.clear()
        assertThat(evaluate(measured = usage, prices = prices)).isEqualTo(first)
    }
}
