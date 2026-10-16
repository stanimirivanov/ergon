package org.ergon.controlplane.resolution.application

import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.ResolutionRunId
import java.time.Instant
import java.util.UUID

/** Exact reviewed intent; authentication supplies the actor, and all capability scope is server-derived. */
data class ExecuteRunCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val expectedSupervisionRevision: RunSupervisionRevision,
    val expectedRunVersion: Long,
    val requestId: UUID,
    val decisionId: UUID,
) {
    init {
        require(expectedRunVersion == 0L) { "only the unprojected pinned step can be executed" }
    }
}

/**
 * Fresh, explicit dispatch intent against an existing reservation, not a new grant.
 * The original actor must currently supervise the run at [expectedSupervisionRevision].
 * Reusing [attemptId] with identical intent acknowledges that attempt without dispatch.
 */
data class ResumeRunExecutionCommand(
    val tenantId: UUID,
    val runId: UUID,
    val actorId: UUID,
    val commandId: UUID,
    val attemptId: UUID,
    val expectedSupervisionRevision: RunSupervisionRevision,
    val expectedRunVersion: Long,
) {
    init {
        require(expectedRunVersion == 0L) { "only an unprojected result can be recovered" }
    }
}

/** Persisted reservation identity and immutable original browser intent; internal IDs never cross the BFF. */
data class StoredRunExecutionCommand(
    val command: ExecuteRunCommand,
    val grantId: CapabilityAuthorizationGrantId,
    val consumptionId: CapabilityAuthorizationConsumptionId,
    val recordedAt: Instant,
)

/** One committed authorization checkpoint for dispatch; absence of a result does not mean absence of an effect. */
data class StoredRunExecutionAttempt(
    val command: ResumeRunExecutionCommand,
    val admittedAt: Instant,
    val recordedAt: Instant,
)

/** Receipt and projection phases are distinct so crashes cannot fabricate terminal execution or proof. */
enum class RunExecutionStatus { INCOMPLETE, RESULT_RECORDED, COMPLETED }

/** Historical command acknowledgement with the original result event, never the run's later current state. */
data class RunExecutionAcknowledgement(
    val stored: StoredRunExecutionCommand,
    val status: RunExecutionStatus,
    val result: StoredResolutionRunCapabilityResult?,
)

/** A committed attempt may dispatch once in this request; replay must only read its acknowledgement. */
data class RunExecutionAdmission(
    val stored: StoredRunExecutionCommand,
    val dispatch: Boolean,
)

/**
 * Immutable execution audit. All writes and namespaced key locks belong to the
 * caller's transaction, after the run lock. Command and attempt namespaces are
 * separate to avoid cross-run lock-order cycles from caller-chosen UUIDs.
 */
interface RunExecutionCommandRepository {
    fun lockCommand(
        tenant: TenantId,
        commandId: UUID,
    )

    fun lockAttempt(
        tenant: TenantId,
        attemptId: UUID,
    )

    fun findCommand(
        tenant: TenantId,
        commandId: UUID,
    ): StoredRunExecutionCommand?

    fun findByRun(
        tenant: TenantId,
        run: ResolutionRunId,
    ): StoredRunExecutionCommand?

    fun findAttempt(
        tenant: TenantId,
        attemptId: UUID,
    ): StoredRunExecutionAttempt?

    /** Prevents adopting any existing internal authorization, not just the latest decision's grant. */
    fun hasPriorAuthorization(
        tenant: TenantId,
        run: ResolutionRunId,
    ): Boolean

    /** Binds the exact grant/consumption chain; caller must then record the first attempt before commit. */
    fun recordCommand(
        command: ExecuteRunCommand,
        consumption: StoredCapabilityAuthorizationConsumption,
        supervision: CurrentRunSupervision,
    ): StoredRunExecutionCommand

    /** Binds the current supervisor epoch and resolver attestation at the final admission instant. */
    fun recordAttempt(
        command: ResumeRunExecutionCommand,
        supervision: CurrentRunSupervision,
        evidence: ApprovalAuthorityEvidenceId,
        admittedAt: Instant,
    ): StoredRunExecutionAttempt
}

class RunExecutionDisabledException : RuntimeException("browser capability execution is disabled")

class RunExecutionConflictException : RuntimeException("execution intent is stale or unavailable")

/** Unknown connector outcome only. Database and invariant failures are deliberately not reclassified. */
class RunExecutionConnectorUncertainException(
    cause: Exception,
) : RuntimeException("connector outcome is uncertain", cause)
