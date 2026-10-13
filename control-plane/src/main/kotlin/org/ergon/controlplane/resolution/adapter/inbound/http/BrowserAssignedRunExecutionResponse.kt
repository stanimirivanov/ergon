package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import java.time.Instant

/**
 * Browser-safe terminal result attributed to the run's exact pinned capability.
 *
 * Completion is the connector's claim, not run-state advancement or outcome
 * verification. [timing] is absent for historical/older-writer receipts. No
 * grant, consumption, provider-operation reference, raw payload or cost is
 * disclosed. This is one gateway call, not a multi-step or provider span tree.
 */
data class BrowserAssignedRunExecutionResponse(
    val stepId: String,
    val capability: String,
    val connector: String,
    val outcome: String,
    val completedAt: Instant,
    val recordedAt: Instant,
    val timing: BrowserInvocationTimingResponse?,
)

/**
 * Runtime timing, independent of differences between wall-clock timestamps.
 *
 * [elapsedNanoseconds] is a canonical non-negative decimal integer string so
 * JavaScript readers cannot lose precision above Number.MAX_SAFE_INTEGER.
 * [source] pins runtime gateway timing semantics; it does not attest provider
 * internals. Cost is unsupported and never inferred from duration or connector.
 */
data class BrowserInvocationTimingResponse(
    val source: String,
    val startedAt: Instant,
    val elapsedNanoseconds: String,
)

internal fun StoredCapabilityInvocationReceipt.toBrowserExecution(): BrowserAssignedRunExecutionResponse =
    BrowserAssignedRunExecutionResponse(
        stepId = receipt.stepId.value,
        capability = receipt.capability.value,
        connector = receipt.connector.value,
        outcome = receipt.outcome.name,
        completedAt = receipt.completedAt,
        recordedAt = recordedAt,
        timing =
            receipt.timing?.let {
                BrowserInvocationTimingResponse(
                    source = "ergon.dev/measurement/connector-call/v1",
                    startedAt = it.startedAt,
                    elapsedNanoseconds = it.elapsedNanoseconds.toString(),
                )
            },
    )
