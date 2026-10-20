package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityInvocationTiming
import java.time.Instant
import java.util.UUID

/** Fresh server-owned identity for each gateway preparation, never a browser replay coordinate. */
fun interface CapabilityInvocationAttemptIdentityGenerator {
    fun next(): UUID
}

/** Committed before I/O; a crash afterwards can leave this record without ever calling the provider. */
data class CapabilityInvocationPreparation(
    val id: UUID,
    val consumptionId: CapabilityAuthorizationConsumptionId,
    val preparedAt: Instant,
)

/**
 * Gateway-reported success/failure, or a thrown call with an unresolved provider
 * outcome. None is independent proof.
 */
enum class CapabilityInvocationCallOutcome { SUCCEEDED, FAILED, UNKNOWN }

/**
 * One caller's measured gateway return/exception, not the winning receipt's timing.
 * Wall observations may move backwards; only [timing] establishes elapsed time.
 * UNKNOWN creates no terminal receipt and conveys no safe-to-retry conclusion.
 */
data class CapabilityInvocationCompletion(
    val outcome: CapabilityInvocationCallOutcome,
    val completedAt: Instant,
    val timing: CapabilityInvocationTiming,
)

/**
 * Append-only audit within the caller's short application transaction. Preparation
 * commits before I/O; terminal completion commits with receipt recording. A failed
 * write propagates, never becoming absence or connector uncertainty. Identities
 * are unique, not idempotency keys: conflicting/duplicate writes must fail.
 */
interface CapabilityInvocationAttemptRepository {
    fun prepare(
        tenant: TenantId,
        preparation: CapabilityInvocationPreparation,
    )

    fun complete(
        tenant: TenantId,
        attemptId: UUID,
        completion: CapabilityInvocationCompletion,
    )
}
