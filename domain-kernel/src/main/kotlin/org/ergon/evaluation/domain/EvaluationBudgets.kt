package org.ergon.evaluation.domain

import java.math.BigInteger
import java.util.List.copyOf

/** Currency nanos are exact billionths of the named currency; no conversion or rounding is performed. */
data class EvaluationBudget(
    val currency: String,
    val maxCostNanos: Long?,
    val maxLatencyNanoseconds: Long?,
) {
    init {
        require(CURRENCY.matches(currency))
        require(maxCostNanos == null || maxCostNanos >= 0)
        require(maxLatencyNanoseconds == null || maxLatencyNanoseconds >= 0)
    }
}

enum class BudgetUnit { CALL, INPUT_TOKEN, OUTPUT_TOKEN }

/** Explicit prices per unit at an exact immutable artifact. Missing rates are unknown, never free. */
class BudgetPricing(
    val artifact: SimulationArtifact,
    val currency: String,
    rates: Map<BudgetUnit, Long>,
) {
    val rates: Map<BudgetUnit, Long> = java.util.Map.copyOf(rates)

    init {
        require(CURRENCY.matches(currency))
        require(rates.values.all { it >= 0 })
    }
}

/** One actual attempted call. Nullable counts/durations mean unmeasured; terminal receipt replay adds no attempt. */
class BudgetAttempt(
    val id: String,
    val pricing: SimulationArtifact,
    units: Map<BudgetUnit, Long?>,
    val latencyNanoseconds: Long?,
) {
    val units: Map<BudgetUnit, Long?> =
        java.util.Collections.unmodifiableMap(
            BudgetUnit.entries.filter { it in units }.associateWith { units[it] },
        )

    init {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")))
        require(units.isNotEmpty() && units.values.all { it == null || it >= 0 })
        require(latencyNanoseconds == null || latencyNanoseconds >= 0)
    }
}

/** Empty means observed zero attempts. A missing collection is represented by null at the evaluator boundary. */
class ScenarioBudgetUsage(
    attempts: List<BudgetAttempt>,
) {
    val attempts: List<BudgetAttempt> = copyOf(attempts)

    init {
        require(attempts.size <= MAX_ATTEMPTS)
        require(attempts.map { it.id }.distinct().size == attempts.size)
    }
}

enum class BudgetStatus { PASS, FAIL, UNKNOWN, ERROR, NOT_CONFIGURED }

enum class BudgetMeasurementReason { MISSING_USAGE, MISSING_PRICING, MISSING_DURATION, CURRENCY_MISMATCH, OVERFLOW }

sealed interface BudgetMeasurement {
    data class Known(
        val value: Long,
    ) : BudgetMeasurement

    data class Unknown(
        val reason: BudgetMeasurementReason,
    ) : BudgetMeasurement

    data class Error(
        val reason: BudgetMeasurementReason,
    ) : BudgetMeasurement
}

data class BudgetDimensionResult(
    val limit: Long?,
    val measurement: BudgetMeasurement,
    val status: BudgetStatus,
)

/** Per-dimension measurements remain visible even when the corresponding limit is unconfigured. */
data class EvaluationBudgetResult(
    val currency: String,
    val cost: BudgetDimensionResult,
    val simulatedLatency: BudgetDimensionResult,
) {
    val configuredBudgetsPassed: Boolean get() =
        listOf(cost, simulatedLatency).filter { it.limit != null }.let { configured ->
            configured.isNotEmpty() && configured.all { it.status == BudgetStatus.PASS }
        }
}

/** Pure exact accounting. Unknown data never yields a partial or zero total; known overflow takes precedence. */
object EvaluationBudgetEvaluator {
    fun evaluate(
        budget: EvaluationBudget,
        usage: ScenarioBudgetUsage?,
        prices: List<BudgetPricing>,
    ): EvaluationBudgetResult {
        require(prices.size <= MAX_ATTEMPTS)
        require(prices.map { it.artifact }.distinct().size == prices.size)
        val cost =
            if (usage == null) {
                BudgetMeasurement.Unknown(BudgetMeasurementReason.MISSING_USAGE)
            } else {
                cost(budget.currency, usage, prices.associateBy { it.artifact })
            }
        val latency =
            if (usage == null) {
                BudgetMeasurement.Unknown(BudgetMeasurementReason.MISSING_USAGE)
            } else {
                latency(usage)
            }
        return EvaluationBudgetResult(
            budget.currency,
            dimension(budget.maxCostNanos, cost),
            dimension(budget.maxLatencyNanoseconds, latency),
        )
    }

    private fun cost(
        currency: String,
        usage: ScenarioBudgetUsage,
        prices: Map<SimulationArtifact, BudgetPricing>,
    ): BudgetMeasurement {
        var total = BigInteger.ZERO
        var unknown: BudgetMeasurementReason? = null
        var currencyMismatch = false
        usage.attempts.forEach { attempt ->
            val pricing = prices[attempt.pricing]
            if (pricing != null && pricing.currency != currency) currencyMismatch = true
            attempt.units.forEach { (unit, count) ->
                val rate = pricing?.rates?.get(unit)
                when {
                    count == null -> unknown = unknown ?: BudgetMeasurementReason.MISSING_USAGE
                    rate == null -> unknown = unknown ?: BudgetMeasurementReason.MISSING_PRICING
                    else -> total += BigInteger.valueOf(count) * BigInteger.valueOf(rate)
                }
            }
        }
        return if (currencyMismatch) {
            BudgetMeasurement.Error(BudgetMeasurementReason.CURRENCY_MISMATCH)
        } else {
            measurement(total, unknown)
        }
    }

    private fun latency(usage: ScenarioBudgetUsage): BudgetMeasurement {
        var total = BigInteger.ZERO
        var missing = false
        usage.attempts.forEach { attempt ->
            val duration = attempt.latencyNanoseconds
            if (duration == null) missing = true else total += BigInteger.valueOf(duration)
        }
        return measurement(total, if (missing) BudgetMeasurementReason.MISSING_DURATION else null)
    }

    private fun measurement(
        total: BigInteger,
        unknown: BudgetMeasurementReason?,
    ): BudgetMeasurement =
        when {
            total > BigInteger.valueOf(Long.MAX_VALUE) -> BudgetMeasurement.Error(BudgetMeasurementReason.OVERFLOW)
            unknown != null -> BudgetMeasurement.Unknown(unknown)
            else -> BudgetMeasurement.Known(total.longValueExact())
        }

    private fun dimension(
        limit: Long?,
        measurement: BudgetMeasurement,
    ): BudgetDimensionResult {
        val status =
            when {
                limit == null -> BudgetStatus.NOT_CONFIGURED
                measurement is BudgetMeasurement.Error -> BudgetStatus.ERROR
                measurement is BudgetMeasurement.Unknown -> BudgetStatus.UNKNOWN
                measurement is BudgetMeasurement.Known && measurement.value <= limit -> BudgetStatus.PASS
                else -> BudgetStatus.FAIL
            }
        return BudgetDimensionResult(limit, measurement, status)
    }
}

private val CURRENCY = Regex("[A-Z]{3}")
private const val MAX_ATTEMPTS = 200
