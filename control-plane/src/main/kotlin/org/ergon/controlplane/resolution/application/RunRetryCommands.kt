package org.ergon.controlplane.resolution.application

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.StepRisk
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunPlan
import java.time.Instant
import java.util.UUID

// Leave room for the next revision while preserving exact JSON integer round-trips.
private const val MAX_BROWSER_RETRY_CASE_VERSION = 9_007_199_254_740_990L
private const val MAX_REVIEWED_RETRY_LABEL_LENGTH = 200

/**
 * Narrow reviewed successor inputs, not evidence content or execution authority. All fields are exact-match fences.
 * Case versions must be positive browser-exact integers; contract revisions are
 * positive and labels contain 1..200 characters.
 * @throws IllegalArgumentException when a coordinate violates those constraints.
 */
data class ReviewedRunRetryPlan(
    val caseStreamVersion: Long,
    val contractKey: String,
    val contractRevision: Int,
    val policyRevision: String,
    val stepId: String,
    val capability: String,
    val effectiveRisk: StepRisk,
    val requiredApproval: ApprovalRequirement,
) {
    init {
        require(caseStreamVersion in 1..MAX_BROWSER_RETRY_CASE_VERSION) { "case version is outside the browser range" }
        require(contractRevision > 0) { "contract revision must be positive" }
        require(
            listOf(contractKey, policyRevision, stepId, capability).all {
                it.isNotBlank() && it.length <= MAX_REVIEWED_RETRY_LABEL_LENGTH
            },
        ) {
            "reviewed plan labels must contain 1..200 characters"
        }
    }

    companion object {
        internal fun from(plan: ResolutionRunPlan): ReviewedRunRetryPlan =
            ReviewedRunRetryPlan(
                plan.caseStreamVersion,
                plan.contract.key.value,
                plan.contract.revision.value,
                plan.policyRevision.value,
                plan.stepId.value,
                plan.capability.value,
                plan.decision.effectiveRisk,
                plan.decision.requiredApproval,
            )
    }
}

/** The inbound session supplies actor/tenant; the browser supplies only immutable reviewed intent. */
data class RetrySupervisedRunCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val expectedSupervisionRevision: RunSupervisionRevision,
    val expectedRunVersion: Long,
    val retryPolicyRevision: String,
    val maximumAttempts: Int,
    val plan: ReviewedRunRetryPlan,
) {
    init {
        require(expectedRunVersion == 1L) { "retry requires the failed sequence-one snapshot" }
        require(maximumAttempts > 0) { "retry ceiling must be positive" }
        require(retryPolicyRevision.isNotBlank() && retryPolicyRevision.length <= MAX_REVIEWED_RETRY_LABEL_LENGTH) {
            "invalid retry policy revision"
        }
    }
}

/** Immutable acknowledgement; successor identity is historical, not permission to inspect its current content. */
data class StoredRunRetryCommand(
    val command: RetrySupervisedRunCommand,
    val successorRunId: UUID,
    val assignmentId: UUID,
    val authorityEvidenceId: UUID,
    val retryEventId: UUID,
    val sourceAttemptNumber: Int,
    val supervisedAt: Instant,
    val startedAt: Instant,
    val recordedAt: Instant,
)

data class RunRetryCommandRecording(
    val stored: StoredRunRetryCommand,
    val created: Boolean,
)

/**
 * Caller-owned transaction: lock run, command, then predecessor state;
 * insert only after runtime retry and assignment.
 */
interface RunRetryCommandRepository {
    /** Serializes tenant command reuse across different runs without exposing existing receipts. */
    fun lockCommand(
        tenantId: TenantId,
        commandId: UUID,
    )

    /** Returns a tenant-scoped receipt or null; the caller must check its originating actor before disclosure. */
    fun find(
        tenantId: TenantId,
        commandId: UUID,
    ): StoredRunRetryCommand?

    /** Records exact runtime/assignment links. Deferred FKs must be satisfied before this transaction commits. */
    fun create(
        tenantId: TenantId,
        command: StoredRunRetryCommand,
    ): StoredRunRetryCommand
}

class RunRetryDisabledException : RuntimeException("browser run retry is disabled")

/** Stale or unavailable retry intent; no new successor or assignment is committed. */
class RunRetryConflictException : RuntimeException("browser run retry conflicts with reviewed intent")
