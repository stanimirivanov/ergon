package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import java.time.Instant
import java.util.UUID

internal const val MAX_RUN_SUPERVISOR_CANDIDATE_PAGE_SIZE = 100

/** Handover-only audit writes; callers hold the tenant/run lock through append and projection update. */
interface RunSupervisorHandoverRepository {
    /** Serializes tenant/command replay across different runs; call after taking the run lock. */
    fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    )

    /** Returns a tenant-scoped historical command receipt, never current supervision truth. */
    fun findByCommandId(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunSupervisorHandover?

    /**
     * Atomically appends [handover] and advances its current projection by one epoch.
     * The surrounding application transaction owns commit/rollback and the run lock.
     * @throws IllegalStateException if the expected preceding projection is inconsistent.
     */
    fun record(
        tenantId: TenantId,
        handover: RunSupervisorHandover,
    ): StoredRunSupervisorHandover
}

/** Minimal identity query consumed only after the exact-current-supervisor gate succeeds. */
fun interface RunSupervisorCandidateReader {
    /**
     * Returns at most [limit] other registered tenant resolvers in ascending UUID order.
     * Current tenant-wide authority must hold at [at]; provider identities never leave this port.
     * The cursor is private, best-effort traversal, not a stable snapshot or eligibility promise.
     */
    fun listCandidates(
        tenantId: TenantId,
        excludedActorId: HumanActorId,
        at: Instant,
        limit: Int,
        afterActorId: UUID?,
    ): List<HumanActorId>
}

/** Bounded recipient identities and the next private cursor, scoped to one observed supervision epoch. */
data class RunSupervisorCandidatePage(
    val actorIds: List<HumanActorId>,
    val nextActorId: UUID?,
    val supervisionRevision: RunSupervisionRevision,
)
