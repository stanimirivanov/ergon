package org.ergon.resolution.domain

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStepId
import java.time.Instant
import java.util.UUID

/** Closed set of terminal outcomes reported by a capability connector. */
enum class CapabilityInvocationOutcome {
    SUCCEEDED,
    FAILED,
}

/**
 * Opaque reference to the connector's durable record of an invocation.
 *
 * The value is normalized for storage and display but its internal structure
 * remains owned by the connector.
 */
@JvmInline
value class ProviderOperationReference private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 500

        /**
         * Creates a non-blank provider reference after trimming whitespace.
         *
         * @throws IllegalArgumentException when [value] is blank or longer than [MAX_LENGTH].
         */
        fun of(value: String): ProviderOperationReference {
            val normalized = value.trim()
            require(normalized.isNotEmpty()) { "provider operation reference must not be blank" }
            require(normalized.length <= MAX_LENGTH) {
                "provider operation reference must not exceed $MAX_LENGTH characters"
            }
            return ProviderOperationReference(normalized)
        }
    }
}

/** Terminal connector result used to create a durable receipt. */
data class CapabilityInvocationResult(
    val outcome: CapabilityInvocationOutcome,
    val providerOperationReference: ProviderOperationReference,
)

/** Complete immutable values needed to rehydrate a validated invocation receipt. */
data class CapabilityInvocationReceiptSnapshot(
    val authorizationConsumptionId: CapabilityAuthorizationConsumptionId,
    val authorizationGrantId: CapabilityAuthorizationGrantId,
    val runId: ResolutionRunId,
    val caseId: CaseId,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val connector: ConnectorName,
    val idempotencyKey: UUID,
    val outcome: CapabilityInvocationOutcome,
    val providerOperationReference: ProviderOperationReference,
    val consumptionConsumedAt: Instant,
    val completedAt: Instant,
)

/**
 * Immutable evidence of the terminal result returned by a connector.
 *
 * [idempotencyKey] must equal [authorizationConsumptionId]; retries therefore
 * address the same provider operation even if the process fails after the
 * external call but before this receipt becomes durable. A receipt proves only
 * what the connector reported, not that the case outcome was independently
 * verified.
 */
@ConsistentCopyVisibility
data class CapabilityInvocationReceipt private constructor(
    val authorizationConsumptionId: CapabilityAuthorizationConsumptionId,
    val authorizationGrantId: CapabilityAuthorizationGrantId,
    val runId: ResolutionRunId,
    val caseId: CaseId,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val connector: ConnectorName,
    val idempotencyKey: UUID,
    val outcome: CapabilityInvocationOutcome,
    val providerOperationReference: ProviderOperationReference,
    val consumptionConsumedAt: Instant,
    val completedAt: Instant,
) {
    companion object {
        /**
         * Records [result] against the exact authorization consumption that permitted the call.
         *
         * Completion may occur after the original grant expires because the
         * grant was irrevocably reserved while current.
         *
         * @throws IllegalArgumentException when completion predates consumption.
         */
        fun complete(
            consumption: CapabilityAuthorizationConsumption,
            result: CapabilityInvocationResult,
            completedAt: Instant,
        ): CapabilityInvocationReceipt {
            require(!completedAt.isBefore(consumption.consumedAt)) {
                "capability invocation completion predates authorization consumption"
            }
            return CapabilityInvocationReceipt(
                authorizationConsumptionId = consumption.id,
                authorizationGrantId = consumption.authorizationGrantId,
                runId = consumption.runId,
                caseId = consumption.caseId,
                policyRevision = consumption.policyRevision,
                stepId = consumption.stepId,
                capability = consumption.capability,
                connector = consumption.connector,
                idempotencyKey = consumption.id.value,
                outcome = result.outcome,
                providerOperationReference = result.providerOperationReference,
                consumptionConsumedAt = consumption.consumedAt,
                completedAt = completedAt,
            )
        }

        /**
         * Reconstructs a receipt read from constrained durable storage.
         *
         * @throws IllegalArgumentException when its key does not identify its
         *   consumption or completion predates consumption.
         */
        fun rehydrate(snapshot: CapabilityInvocationReceiptSnapshot): CapabilityInvocationReceipt {
            require(snapshot.idempotencyKey == snapshot.authorizationConsumptionId.value) {
                "capability invocation idempotency key must identify its authorization consumption"
            }
            require(!snapshot.completedAt.isBefore(snapshot.consumptionConsumedAt)) {
                "capability invocation completion predates authorization consumption"
            }
            return CapabilityInvocationReceipt(
                authorizationConsumptionId = snapshot.authorizationConsumptionId,
                authorizationGrantId = snapshot.authorizationGrantId,
                runId = snapshot.runId,
                caseId = snapshot.caseId,
                policyRevision = snapshot.policyRevision,
                stepId = snapshot.stepId,
                capability = snapshot.capability,
                connector = snapshot.connector,
                idempotencyKey = snapshot.idempotencyKey,
                outcome = snapshot.outcome,
                providerOperationReference = snapshot.providerOperationReference,
                consumptionConsumedAt = snapshot.consumptionConsumedAt,
                completedAt = snapshot.completedAt,
            )
        }
    }
}
