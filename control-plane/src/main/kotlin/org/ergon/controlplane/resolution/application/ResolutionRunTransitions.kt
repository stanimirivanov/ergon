package org.ergon.controlplane.resolution.application

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.ResolutionRunCapabilityResult
import org.ergon.resolution.domain.ResolutionRunCapabilityResultBasis
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import java.time.Instant
import java.util.UUID

/** Immutable run event paired with its database recording instant. */
data class StoredResolutionRunCapabilityResult(
    val event: ResolutionRunCapabilityResult,
    val recordedAt: Instant,
)

/** Result of appending or replaying the run event for a connector receipt. */
data class ResolutionRunCapabilityResultRecording(
    val storedEvent: StoredResolutionRunCapabilityResult,
    val currentState: ResolutionRunStateSnapshot,
    val created: Boolean,
)

/** Durable append and current-state projection boundary for resolution runs. */
interface ResolutionRunTransitionRepository {
    /**
     * Locks and returns the tenant-scoped current state for [runId].
     *
     * The lock must remain held through [append] in the caller's transaction so
     * sequence allocation and projection update are serialized.
     */
    fun lockState(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): ResolutionRunStateSnapshot?

    /** @return the run event already derived from [consumptionId], or `null` before append. */
    fun findByReceipt(
        tenantId: TenantId,
        consumptionId: CapabilityAuthorizationConsumptionId,
    ): StoredResolutionRunCapabilityResult?

    /**
     * Appends [event] and advances its current-state projection atomically.
     *
     * The caller must hold the state lock obtained from [lockState].
     *
     * @throws IllegalStateException when the projection version no longer
     *   matches the event sequence precondition.
     */
    fun append(
        tenantId: TenantId,
        event: ResolutionRunCapabilityResult,
    ): ResolutionRunCapabilityResultRecording
}

/** Supplies unpredictable run-event identities without coupling the use case to UUID generation. */
fun interface ResolutionRunEventIdentityGenerator {
    /** @return a fresh identity suitable for immutable run history. */
    fun next(): ResolutionRunEventId
}

/** Signals that a run has no terminal connector receipt to record yet. */
class ResolutionRunCapabilityReceiptNotFoundException(
    runId: UUID,
) : RuntimeException("resolution run $runId has no capability invocation receipt")

/** Appends one connector result to run history and advances current execution state. */
class ResolutionRunCapabilityResultService(
    private val runs: ResolutionRunRepository,
    private val receipts: CapabilityInvocationReceiptRepository,
    private val transitions: ResolutionRunTransitionRepository,
    private val identities: ResolutionRunEventIdentityGenerator,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Records the run's terminal connector receipt exactly once.
     *
     * Success advances only to `VERIFYING`; it never closes the run or case.
     * Replays return the original event and current state without allocating a
     * new identity.
     *
     * @throws ResolutionRunNotFoundException when [runId] is absent from [tenantId].
     * @throws ResolutionRunCapabilityReceiptNotFoundException before a terminal
     *   connector receipt exists for the run.
     */
    fun record(
        tenantId: UUID,
        runId: UUID,
    ): ResolutionRunCapabilityResultRecording =
        transactionRunner.required {
            val scopedTenantId = TenantId(tenantId)
            val scopedRunId = ResolutionRunId(runId)
            val run =
                runs.find(scopedTenantId, scopedRunId)?.run
                    ?: throw ResolutionRunNotFoundException(runId)
            val currentState =
                checkNotNull(transitions.lockState(scopedTenantId, scopedRunId)) {
                    "resolution run current-state projection is missing"
                }
            val receipt =
                receipts.findByRun(scopedTenantId, scopedRunId)?.receipt
                    ?: throw ResolutionRunCapabilityReceiptNotFoundException(runId)
            transitions.findByReceipt(scopedTenantId, receipt.authorizationConsumptionId)?.let {
                return@required ResolutionRunCapabilityResultRecording(it, currentState, created = false)
            }
            val event =
                try {
                    ResolutionRunCapabilityResult.record(
                        identities.next(),
                        ResolutionRunCapabilityResultBasis(run, currentState, receipt),
                    )
                } catch (exception: IllegalArgumentException) {
                    // All inputs are constrained durable records, so disagreement means corruption.
                    throw IllegalStateException("stored resolution run transition sources are inconsistent", exception)
                }
            transitions.append(scopedTenantId, event)
        }
}
