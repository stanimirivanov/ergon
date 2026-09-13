package org.ergon.resolution.domain

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.ResolutionStepId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Stable identity of one immutable request for human authority. */
@JvmInline
value class ApprovalRequestId(
    val value: UUID,
)

/** Human role whose approval a run requires; unlike a contract declaration, this cannot be `NONE`. */
enum class ApprovalAuthority {
    REQUESTER,
    RESOLVER,
}

/** Clock-derived lifecycle of an approval request; the status is not stored or manually changed. */
enum class ApprovalRequestStatus {
    PENDING,
    EXPIRED,
}

/**
 * Immutable request for the human authority required by one run step.
 *
 * The half-open validity interval begins at [requestedAt] and ends at
 * [expiresAt]; the request is expired at the exact end instant. Its maximum
 * lifetime is [MAX_LIFETIME], limiting how long later approval can rely on the
 * original evidence and policy snapshot.
 *
 * This request records a prerequisite only. It does not identify an actor,
 * prove the actor has [authority], or grant permission to execute.
 *
 * @throws IllegalArgumentException when the validity interval is empty,
 *   negative, or longer than [MAX_LIFETIME].
 */
data class ApprovalRequest(
    val id: ApprovalRequestId,
    val runId: ResolutionRunId,
    val stepId: ResolutionStepId,
    val authority: ApprovalAuthority,
    val requestedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(expiresAt.isAfter(requestedAt)) { "approval request expiry must be after its request time" }
        require(Duration.between(requestedAt, expiresAt) <= MAX_LIFETIME) {
            "approval request lifetime must not exceed $MAX_LIFETIME"
        }
    }

    /** Returns [EXPIRED][ApprovalRequestStatus.EXPIRED] at and after [expiresAt]. */
    fun statusAt(now: Instant): ApprovalRequestStatus =
        if (now.isBefore(expiresAt)) ApprovalRequestStatus.PENDING else ApprovalRequestStatus.EXPIRED

    companion object {
        val MAX_LIFETIME: Duration = Duration.ofHours(24)

        /**
         * Requests the human approval declared by [run] for a bounded [lifetime].
         *
         * @throws IllegalArgumentException when the run requires no human
         *   approval, is not waiting for approval, or [lifetime] is outside
         *   `(Duration.ZERO, MAX_LIFETIME]`.
         */
        fun create(
            id: ApprovalRequestId,
            run: ResolutionRunStart,
            requestedAt: Instant,
            lifetime: Duration,
        ): ApprovalRequest {
            require(run.initialState == ResolutionRunInitialState.WAITING_FOR_APPROVAL) {
                "resolution run is not waiting for approval"
            }
            require(!lifetime.isZero && !lifetime.isNegative && lifetime <= MAX_LIFETIME) {
                "approval request lifetime must be positive and not exceed $MAX_LIFETIME"
            }
            val authority =
                when (run.requiredApproval) {
                    ApprovalRequirement.REQUESTER -> ApprovalAuthority.REQUESTER
                    ApprovalRequirement.RESOLVER -> ApprovalAuthority.RESOLVER
                    ApprovalRequirement.NONE -> error("run waiting for approval must require a human authority")
                }
            return ApprovalRequest(
                id = id,
                runId = run.id,
                stepId = run.stepId,
                authority = authority,
                requestedAt = requestedAt,
                expiresAt = requestedAt.plus(lifetime),
            )
        }
    }
}
