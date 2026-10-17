package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import java.time.Instant
import java.util.UUID

private const val MAX_ESCALATION_POLICY_LABEL_LENGTH = 200

/**
 * Exact reviewed terminal-handoff intent. Session identity supplies actor/tenant;
 * no case evidence, queue selection or recipient is accepted from the browser.
 * Run version must be one, attempts positive and policy labels 1..200 characters.
 * @throws IllegalArgumentException for invalid coordinates or non-exhausted intent.
 */
data class EscalateSupervisedRunCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val expectedSupervisionRevision: RunSupervisionRevision,
    val expectedRunVersion: Long,
    val sourceAttemptNumber: Int,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val reason: ResolutionRunEscalationReason,
) {
    init {
        require(expectedRunVersion == 1L) { "escalation requires the failed sequence-one snapshot" }
        require(maximumAttempts > 0 && sourceAttemptNumber >= maximumAttempts) { "retry budget is not exhausted" }
        require(retryPolicyRevision.isNotBlank() && retryPolicyRevision.length <= MAX_ESCALATION_POLICY_LABEL_LENGTH) {
            "invalid retry policy revision"
        }
    }
}

/**
 * Immutable audit, including private attribution that must not cross the BFF.
 * Follow-up identity acknowledges creation, never current ownership or access.
 */
data class StoredRunEscalationCommand(
    val command: EscalateSupervisedRunCommand,
    val supervisedAt: Instant,
    val authorityEvidenceId: UUID,
    val escalationEventId: UUID,
    val followUpWorkItemId: UUID,
    val requestedAt: Instant,
    val recordedAt: Instant,
)

data class RunEscalationCommandRecording(
    val stored: StoredRunEscalationCommand,
    val created: Boolean,
)

/** Caller owns one transaction: run lock, tenant command lock, state lock, runtime escalation, audit. */
interface RunEscalationCommandRepository {
    /** Serializes command reuse across runs without disclosing any existing receipt. */
    fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    )

    /** Tenant-scoped history; the caller must enforce actor and exact intent before disclosure. */
    fun find(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunEscalationCommand?

    /** Inserts only after the runtime event and follow-up exist in the same transaction. */
    fun create(
        tenantId: TenantId,
        command: StoredRunEscalationCommand,
    ): StoredRunEscalationCommand
}

class RunEscalationDisabledException : RuntimeException("browser run escalation is disabled")

/** Stale, changed or already-handled intent; no new terminal decision or follow-up commits. */
class RunEscalationConflictException : RuntimeException("run escalation conflicts with reviewed intent")
