package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.ergon.resolution.domain.CapabilityInvocationTiming
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Exact authorized input presented to a capability connector. */
data class CapabilityInvocationCommand(
    val tenantId: TenantId,
    val consumption: CapabilityAuthorizationConsumption,
) {
    /** Stable provider idempotency key reused by every retry of this consumption. */
    val idempotencyKey: UUID = consumption.id.value
}

/**
 * Invokes the connector selected when authorization was consumed.
 *
 * Implementations must pass [CapabilityInvocationCommand.idempotencyKey] to
 * the provider and return the same terminal operation for every retry with that
 * key. A thrown exception means no terminal result is known and no receipt may
 * be fabricated by the caller.
 */
fun interface CapabilityConnectorGateway {
    fun invoke(command: CapabilityInvocationCommand): CapabilityInvocationResult
}

/**
 * Infallible monotonic timestamps used only as differences around connector I/O.
 *
 * Origins are arbitrary and readings may be negative or wrap. Subtraction must
 * produce a non-negative elapsed duration for calls shorter than 2^63 nanoseconds.
 * Implementations do not read a wall clock; deterministic tests supply readings.
 */
fun interface CapabilityInvocationTicker {
    fun readNanoseconds(): Long
}

/** Immutable connector receipt paired with database recording metadata. */
data class StoredCapabilityInvocationReceipt(
    val receipt: CapabilityInvocationReceipt,
    val recordedAt: Instant,
)

/** Result of idempotently invoking or replaying one authorization consumption. */
data class CapabilityInvocationExecution(
    val storedReceipt: StoredCapabilityInvocationReceipt,
    val created: Boolean,
)

/** Durable tenant-scoped store of terminal connector receipts. */
interface CapabilityInvocationReceiptRepository {
    /** @return the receipt for [consumptionId], or `null` when no terminal result is durable. */
    fun find(
        tenantId: TenantId,
        consumptionId: CapabilityAuthorizationConsumptionId,
    ): StoredCapabilityInvocationReceipt?

    /** @return the single receipt recorded for [runId], or `null` before its connector result exists. */
    fun findByRun(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredCapabilityInvocationReceipt?

    /**
     * Stores [receipt], or returns the concurrently stored receipt for the same consumption.
     *
     * Call inside [TransactionRunner.required] with an already persisted consumption.
     * Implementations serialize competing receipt writes until that transaction
     * completes; callers must not hold this boundary across connector I/O.
     * Other consumptions reusing a provider operation remain integrity failures,
     * not successful replay.
     *
     * @return an execution whose `created` flag is true only for the transaction
     *   that inserted the immutable row.
     *   The losing caller receives the winner's entire receipt, including its
     *   timing or unmeasured status; measurements are never merged/backfilled.
     * @throws IllegalStateException when no application transaction is active.
     */
    fun createOrFind(
        tenantId: TenantId,
        receipt: CapabilityInvocationReceipt,
    ): CapabilityInvocationExecution
}

/** Signals tenant-scoped consumption absence without revealing another tenant's data. */
class CapabilityAuthorizationConsumptionNotFoundException(
    consumptionId: UUID,
) : RuntimeException("capability authorization consumption $consumptionId was not found")

/** Signals that the selected route has no installed connector implementation. */
class CapabilityConnectorUnavailableException(
    val connector: String,
) : RuntimeException("capability connector $connector is unavailable")

/** Invokes reserved capability work and makes its terminal connector result durable. */
@Suppress("LongParameterList") // Receipt authority and per-call audit have distinct persistence/identity owners.
class CapabilityInvocationService(
    private val consumptions: CapabilityAuthorizationConsumptionRepository,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val connector: CapabilityConnectorGateway,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
    private val ticker: CapabilityInvocationTicker,
    private val attempts: CapabilityInvocationAttemptRepository,
    private val attemptIdentities: CapabilityInvocationAttemptIdentityGenerator,
) {
    /**
     * Invokes [consumptionId] with a stable idempotency key or replays its existing receipt.
     *
     * Database work occurs in transactions on either side of the remote call;
     * no transaction or row lock is held during connector I/O. Concurrent calls
     * may reach the connector, but its idempotency contract makes them the same
     * provider operation and database uniqueness retains one receipt.
     * The new receipt retains this caller's monotonic gateway duration. Wall
     * timestamps are normalized to PostgreSQL microseconds; clock correction
     * does not change elapsed time. Historical replay performs no sampling.
     * Each new call first commits preparation. Its own completion measurement
     * is appended with the terminal receipt, or as UNKNOWN for a thrown gateway
     * exception without a receipt. A preparation alone does not prove dispatch.
     * Clock/invariant and audit defects propagate without fabricated completion.
     *
     * @throws CapabilityAuthorizationConsumptionNotFoundException when the
     *   consumption is absent from [tenantId].
     * @throws CapabilityConnectorUnavailableException when its configured
     *   connector is not installed.
     */
    fun invoke(
        tenantId: UUID,
        consumptionId: UUID,
    ): CapabilityInvocationExecution {
        val scopedTenantId = TenantId(tenantId)
        val scopedConsumptionId = CapabilityAuthorizationConsumptionId(consumptionId)
        val preparation =
            transactionRunner.required {
                val consumption =
                    consumptions.find(scopedTenantId, scopedConsumptionId)?.consumption
                        ?: throw CapabilityAuthorizationConsumptionNotFoundException(consumptionId)
                val receipt = receipts.find(scopedTenantId, scopedConsumptionId)
                if (receipt != null) {
                    InvocationPreparation.Replay(receipt)
                } else {
                    val id = attemptIdentities.next()
                    attempts.prepare(
                        scopedTenantId,
                        CapabilityInvocationPreparation(
                            id,
                            scopedConsumptionId,
                            clock.instant().truncatedTo(ChronoUnit.MICROS),
                        ),
                    )
                    InvocationPreparation.Call(consumption, id)
                }
            }
        return when (preparation) {
            is InvocationPreparation.Replay -> CapabilityInvocationExecution(preparation.receipt, created = false)
            is InvocationPreparation.Call -> invokePrepared(scopedTenantId, preparation)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Only the gateway's thrown boundary becomes uncertainty.
    private fun invokePrepared(
        tenant: TenantId,
        preparation: InvocationPreparation.Call,
    ): CapabilityInvocationExecution {
        val command = CapabilityInvocationCommand(tenant, preparation.consumption)
        val startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val startedTick = ticker.readNanoseconds()
        val result =
            try {
                connector.invoke(command)
            } catch (failure: Exception) {
                val completion = completion(CapabilityInvocationCallOutcome.UNKNOWN, startedAt, startedTick)
                recordUncertainty(tenant, preparation.attemptId, completion, failure)
                throw failure
            }
        val outcome =
            when (result.outcome) {
                CapabilityInvocationOutcome.SUCCEEDED -> CapabilityInvocationCallOutcome.SUCCEEDED
                CapabilityInvocationOutcome.FAILED -> CapabilityInvocationCallOutcome.FAILED
            }
        val completion = completion(outcome, startedAt, startedTick)
        val receipt =
            try {
                CapabilityInvocationReceipt.complete(
                    preparation.consumption,
                    result,
                    completion.completedAt,
                    completion.timing,
                )
            } catch (exception: IllegalArgumentException) {
                // A persisted consumption and injected clock should never contradict one another.
                throw IllegalStateException("capability invocation receipt is inconsistent", exception)
            }
        return transactionRunner.required {
            val recorded = receipts.createOrFind(tenant, receipt)
            attempts.complete(tenant, preparation.attemptId, completion)
            recorded
        }
    }

    @Suppress("TooGenericExceptionCaught") // A storage failure must not become connector uncertainty.
    private fun recordUncertainty(
        tenant: TenantId,
        attemptId: UUID,
        completion: CapabilityInvocationCompletion,
        gatewayFailure: Exception,
    ) {
        try {
            transactionRunner.required { attempts.complete(tenant, attemptId, completion) }
        } catch (auditFailure: Exception) {
            auditFailure.addSuppressed(gatewayFailure)
            throw auditFailure
        }
    }

    private fun completion(
        outcome: CapabilityInvocationCallOutcome,
        startedAt: Instant,
        startedTick: Long,
    ): CapabilityInvocationCompletion {
        val elapsed = ticker.readNanoseconds() - startedTick
        val completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return try {
            CapabilityInvocationCompletion(outcome, completedAt, CapabilityInvocationTiming(startedAt, elapsed))
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("capability invocation measurement is inconsistent", failure)
        }
    }
}

private sealed interface InvocationPreparation {
    data class Replay(
        val receipt: StoredCapabilityInvocationReceipt,
    ) : InvocationPreparation

    data class Call(
        val consumption: CapabilityAuthorizationConsumption,
        val attemptId: UUID,
    ) : InvocationPreparation
}
