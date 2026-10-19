package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Instant
import java.util.UUID

internal const val RUN_CONTROL_LEASE_SECONDS = 120L

/** Supplies a new non-secret lease identity at acquisition; deterministic implementations support replay/race tests. */
fun interface RunControlIdentityGenerator {
    fun next(): UUID
}

/**
 * Public, non-secret fence. Possession grants nothing without session ownership and current authority.
 * [revision] is a positive browser-exact integer, capped at 9,007,199,254,740,990.
 * @throws IllegalArgumentException for a revision outside that range.
 */
data class RunControlFence(
    val leaseId: UUID,
    val revision: Long,
) {
    init {
        require(revision in 1..MAX_RUN_SUPERVISION_REVISION) { "control revision is outside the safe browser range" }
    }
}

/** Trusted adapter context. [holderId] is a server-generated session binding, never a caller-selected identity. */
data class RunCommandControl(
    val fence: RunControlFence,
    val holderId: UUID,
)

enum class RunControlAction { ACQUIRE, RENEW, RELEASE }

/**
 * Exact lifecycle intent; acquire omits [expectedFence], renew/release name their original fence.
 * @throws IllegalArgumentException when the action and expected-fence presence disagree.
 */
data class RunControlCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val supervisionRevision: RunSupervisionRevision,
    val action: RunControlAction,
    val expectedFence: RunControlFence?,
) {
    init {
        require((action == RunControlAction.ACQUIRE) == (expectedFence == null)) { "invalid lifecycle fence" }
    }
}

/**
 * Immutable lifecycle result; positive [sequence] orders history independently of acquisition fences.
 * Non-release snapshots have exactly 120 seconds of validity; renew/release retain the request fence.
 * [recordedAt] is database persistence time, independent of the server-owned lease decision instant.
 * @throws IllegalArgumentException for contradictory fence, sequence or validity coordinates.
 */
data class StoredRunControl(
    val command: RunControlCommand,
    val sequence: Long,
    val fence: RunControlFence,
    val holderId: UUID,
    val changedAt: Instant,
    val expiresAt: Instant,
    val recordedAt: Instant,
    val supervisedAt: Instant,
) {
    init {
        require(sequence > 0) { "control sequence must be positive" }
        require(changedAt.isBefore(expiresAt)) { "control change must precede expiry" }
        require(
            command.action == RunControlAction.ACQUIRE || command.expectedFence == fence,
        ) { "control fence changed without acquisition" }
        val fixedDuration = expiresAt == changedAt.plusSeconds(RUN_CONTROL_LEASE_SECONDS)
        require(command.action == RunControlAction.RELEASE || fixedDuration) {
            "control duration must be server-owned"
        }
    }

    /** Exclusive expiry of this snapshot; only the current head in its supervision epoch may permit admission. */
    fun activeAt(at: Instant): Boolean {
        val withinWindow = !at.isBefore(changedAt) && at.isBefore(expiresAt)
        return command.action != RunControlAction.RELEASE && withinWindow
    }
}

enum class RunControlStatus { DISABLED, AVAILABLE, THIS_SESSION, ANOTHER_SESSION, EXPIRED, TERMINAL }

/** Private observation, never permission: only this session receives a usable fence. Holder identities stay private. */
data class RunControlView(
    val status: RunControlStatus,
    val supervisionRevision: RunSupervisionRevision,
    val observedAt: Instant,
    val fence: RunControlFence? = null,
    val expiresAt: Instant? = null,
)

/** Namespace prevents identical command UUIDs in distinct existing command families from aliasing audit. */
enum class RunControlCommandKind {
    HANDOVER,
    APPROVAL,
    PROOF,
    EXECUTION,
    EXECUTION_RECOVERY,
    RETRY,
    ESCALATION,
    CANCELLATION,
}

data class RunControlCommandKey(
    val tenantId: UUID,
    val runId: UUID,
    val kind: RunControlCommandKind,
    val commandId: UUID,
)

/** Append-only admission audit is committed atomically with the existing command's effects, never with provider I/O. */
data class RunControlAdmission(
    val key: RunControlCommandKey,
    val control: StoredRunControl,
    val admittedAt: Instant,
)

/**
 * Durable lease/history boundary. All writes and head reads require the caller's run-first transaction lock.
 * [lockCommand] additionally serializes a lifecycle UUID across runs. [append] advances the head atomically;
 * implementations must reject mutation of history and cross-tenant/run references.
 */
interface RunControlRepository {
    fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    )

    fun findCommand(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunControl?

    fun current(
        tenant: TenantId,
        run: ResolutionRunId,
    ): StoredRunControl?

    fun append(control: StoredRunControl): StoredRunControl

    fun admission(key: RunControlCommandKey): RunControlAdmission?

    fun recordAdmission(admission: RunControlAdmission)
}

class RunControlRequiredException : RuntimeException("browser control is required")

class RunControlConflictException : RuntimeException("browser control intent is stale or unavailable")

class RunControlDisabledException : RuntimeException("browser control is disabled")
