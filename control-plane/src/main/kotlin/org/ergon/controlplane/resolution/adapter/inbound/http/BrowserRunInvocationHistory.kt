package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.RunInvocationHistory
import java.time.Instant

/**
 * Partial observed coverage only: historical/older-writer calls cannot be
 * reconstructed from admissions or receipts.
 */
data class BrowserRunInvocationHistory(
    val observedAt: Instant,
    val supervisionRevision: Long,
    val entries: List<BrowserInvocationAttempt>,
    val truncated: Boolean,
)

/** Preparation does not prove dispatch; null completion must not be rendered as live/in-flight or as zero latency. */
data class BrowserInvocationAttempt(
    val stepId: String,
    val capability: String,
    val connector: String,
    val preparedAt: Instant,
    val recordedAt: Instant,
    val completion: BrowserInvocationCompletion?,
)

/** UNKNOWN is measured gateway uncertainty, never a terminal provider receipt or proof of failure. */
data class BrowserInvocationCompletion(
    val outcome: String,
    val completedAt: Instant,
    val recordedAt: Instant,
    val timing: BrowserInvocationTimingResponse,
)

internal fun RunInvocationHistory.toBrowserResponse(): BrowserRunInvocationHistory =
    BrowserRunInvocationHistory(
        observedAt,
        supervisionRevision.value,
        attempts.entries.map { attempt ->
            BrowserInvocationAttempt(
                attempt.stepId.value,
                attempt.capability.value,
                attempt.connector.value,
                attempt.preparedAt,
                attempt.recordedAt,
                attempt.completion?.let {
                    BrowserInvocationCompletion(
                        it.call.outcome.name,
                        it.call.completedAt,
                        it.recordedAt,
                        BrowserInvocationTimingResponse(
                            "ergon.dev/measurement/connector-call/v1",
                            it.call.timing.startedAt,
                            it.call.timing.elapsedNanoseconds
                                .toString(),
                        ),
                    )
                },
            )
        },
        attempts.truncated,
    )
