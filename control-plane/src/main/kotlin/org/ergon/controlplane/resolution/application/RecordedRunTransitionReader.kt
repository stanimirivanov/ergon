package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId

/** Sanitized immutable history query consumed by the assigned Console, not an event-writing port. */
fun interface RecordedRunTransitionReader {
    /**
     * Reads the immutable prefix through a previously observed state revision.
     *
     * [throughSequence] is in 0..2 for the current single-capability runtime.
     * Return ascending sequence order, scoped to the exact tenant/run; zero is
     * an empty prefix. Excluding later appends aligns history with a READ COMMITTED
     * state read without a reader lock. The caller validates completeness.
     *
     * @throws IllegalArgumentException when [throughSequence] is outside 0..2.
     * @throws IllegalStateException when stored transition metadata is invalid.
     */
    fun listRecordedTransitions(
        tenantId: TenantId,
        runId: ResolutionRunId,
        throughSequence: Long,
    ): List<RecordedRunTransition>
}
