package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Instant
import java.util.UUID

/** Monotonic supervision epoch, independent of run-state revisions; zero names the initial assignment. */
@JvmInline
value class RunSupervisionRevision(
    val value: Long,
) {
    init {
        // Browser commands must round-trip the epoch exactly as a JSON safe integer.
        require(value in 0..MAX_RUN_SUPERVISION_REVISION) { "supervision revision is outside the supported range" }
    }

    /** Advances the epoch; throws [IllegalArgumentException] when the supported browser range is exhausted. */
    fun next(): RunSupervisionRevision = RunSupervisionRevision(Math.addExact(value, 1))
}

internal const val MAX_RUN_SUPERVISION_REVISION = 9_007_199_254_740_990L

/**
 * Current access relationship paired with the unchanged initial assignment.
 *
 * [supervisorActorId] is current, while [initialAssignment] retains the original
 * machine-attributed assignee and discovery order. Neither record grants execution authority.
 */
data class CurrentRunSupervision(
    val initialAssignment: StoredRunSupervisorAssignment,
    val supervisorActorId: HumanActorId,
    val revision: RunSupervisionRevision,
    val supervisedAt: Instant,
)

/** Immutable transfer intent; the inbound adapter supplies the authenticated outgoing actor. */
data class HandoverRunSupervisorCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val supervisorActorId: UUID,
    val commandId: UUID,
    val expectedRevision: RunSupervisionRevision,
)

/**
 * Durable handover audit fact. Both attestations were checked at [occurredAt].
 *
 * Authority and actor coordinates remain server-side. A replay acknowledges
 * this historical transfer, not present ownership after subsequent transfers.
 */
data class RunSupervisorHandover(
    val runId: ResolutionRunId,
    val assignmentId: UUID,
    val commandId: UUID,
    val revision: RunSupervisionRevision,
    val previousActorId: HumanActorId,
    val supervisorActorId: HumanActorId,
    val previousAuthorityEvidenceId: ApprovalAuthorityEvidenceId,
    val authorityEvidenceId: ApprovalAuthorityEvidenceId,
    val occurredAt: Instant,
) {
    init {
        require(revision.value > 0) { "handover requires a positive supervision revision" }
        require(previousActorId != supervisorActorId) { "handover must select another resolver" }
    }
}

/** Original transfer and database recording instant, returned unchanged on an exact replay. */
data class StoredRunSupervisorHandover(
    val handover: RunSupervisorHandover,
    val recordedAt: Instant,
)

data class RunSupervisorHandoverRecording(
    val stored: StoredRunSupervisorHandover,
    val created: Boolean,
)

/** Disabled during mixed-version deployment; no transfer has been attempted. */
class RunSupervisorHandoverDisabledException : RuntimeException("run supervisor handover is disabled")

/** The caller's current epoch or command intent no longer agrees; no transfer is applied. */
class RunSupervisorHandoverConflictException : RuntimeException("run supervisor handover conflicts with current intent")

/** A terminal run or exhausted supervision epoch cannot change browser supervision. */
class RunSupervisorHandoverUnavailableException : RuntimeException("run supervisor handover is unavailable")
