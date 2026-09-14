package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumption
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationResult
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Clock
import java.time.Instant
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
     * @return an execution whose `created` flag is true only for the transaction
     *   that inserted the immutable row.
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
class CapabilityInvocationService(
    private val consumptions: CapabilityAuthorizationConsumptionRepository,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val connector: CapabilityConnectorGateway,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    /**
     * Invokes [consumptionId] with a stable idempotency key or replays its existing receipt.
     *
     * Database work occurs in transactions on either side of the remote call;
     * no transaction or row lock is held during connector I/O. Concurrent calls
     * may reach the connector, but its idempotency contract makes them the same
     * provider operation and database uniqueness retains one receipt.
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
                InvocationPreparation(
                    consumption = consumption,
                    existingReceipt = receipts.find(scopedTenantId, scopedConsumptionId),
                )
            }
        preparation.existingReceipt?.let {
            return CapabilityInvocationExecution(it, created = false)
        }

        val result = connector.invoke(CapabilityInvocationCommand(scopedTenantId, preparation.consumption))
        val receipt =
            try {
                CapabilityInvocationReceipt.complete(preparation.consumption, result, clock.instant())
            } catch (exception: IllegalArgumentException) {
                // A persisted consumption and injected clock should never contradict one another.
                throw IllegalStateException("capability invocation receipt is inconsistent", exception)
            }
        return transactionRunner.required { receipts.createOrFind(scopedTenantId, receipt) }
    }
}

private data class InvocationPreparation(
    val consumption: CapabilityAuthorizationConsumption,
    val existingReceipt: StoredCapabilityInvocationReceipt?,
)
