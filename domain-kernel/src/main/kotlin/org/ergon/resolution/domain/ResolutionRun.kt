package org.ergon.resolution.domain

import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import java.util.UUID

/** Stable identity of one durable resolution attempt. */
@JvmInline
value class ResolutionRunId(
    val value: UUID,
)

/**
 * Initial run state derived from policy requirements, before authority is evaluated.
 *
 * Neither state permits capability invocation. [READY_FOR_AUTHORIZATION] means
 * only that policy requires no human approval; actor scope and tenant capability
 * availability remain mandatory checks.
 */
enum class ResolutionRunInitialState {
    WAITING_FOR_APPROVAL,
    READY_FOR_AUTHORIZATION,
}

/**
 * Exact ready-plan inputs that must retain one meaning when a run starts.
 *
 * A positive [caseStreamVersion] identifies the final case event included in
 * the evidence snapshot. [decision] can contain requirements only: a denied
 * policy result cannot be represented as a startable plan.
 *
 * @throws IllegalArgumentException when [caseStreamVersion] is not positive.
 */
data class ResolutionRunPlan(
    val caseStreamVersion: Long,
    val contract: ResolutionContractIdentity,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val decision: StepPolicyDecision.Requirements,
) {
    init {
        require(caseStreamVersion > 0) { "run case stream version must be positive" }
    }
}

/**
 * Immutable snapshot from which one resolution attempt begins.
 *
 * [caseStreamVersion] fixes the evidence boundary. Contract and policy identities,
 * the first step, and effective safeguards are copied into the snapshot so later
 * configuration changes cannot alter the meaning of this run.
 * [attemptNumber] starts at one; every later attempt identifies exactly one
 * [predecessorRunId] rather than changing the failed attempt's inputs.
 *
 * @throws IllegalArgumentException when the evidence version is not positive,
 *   high risk has no human approval, or [initialState] contradicts
 *   [requiredApproval], or the attempt position contradicts predecessor presence.
 */
data class ResolutionRunStart(
    val id: ResolutionRunId,
    val caseId: CaseId,
    val caseStreamVersion: Long,
    val contract: ResolutionContractIdentity,
    val policyRevision: ResolutionPolicyRevision,
    val stepId: ResolutionStepId,
    val capability: CapabilityName,
    val effectiveRisk: StepRisk,
    val requiredApproval: ApprovalRequirement,
    val initialState: ResolutionRunInitialState,
    val attemptNumber: Int = 1,
    val predecessorRunId: ResolutionRunId? = null,
) {
    init {
        require(caseStreamVersion > 0) { "run case stream version must be positive" }
        require(effectiveRisk != StepRisk.HIGH || requiredApproval != ApprovalRequirement.NONE) {
            "high-risk run requires requester or resolver approval"
        }
        require(
            initialState ==
                if (requiredApproval == ApprovalRequirement.NONE) {
                    ResolutionRunInitialState.READY_FOR_AUTHORIZATION
                } else {
                    ResolutionRunInitialState.WAITING_FOR_APPROVAL
                },
        ) { "run initial state must match its approval requirement" }
        require(attemptNumber > 0) { "run attempt number must be positive" }
        require((attemptNumber == 1) == (predecessorRunId == null)) {
            "only the first run attempt may omit a predecessor"
        }
    }

    companion object {
        /**
         * Captures an allowed policy decision as an immutable run start.
         *
         */
        fun create(
            id: ResolutionRunId,
            caseId: CaseId,
            plan: ResolutionRunPlan,
        ): ResolutionRunStart =
            ResolutionRunStart(
                id = id,
                caseId = caseId,
                caseStreamVersion = plan.caseStreamVersion,
                contract = plan.contract,
                policyRevision = plan.policyRevision,
                stepId = plan.stepId,
                capability = plan.capability,
                effectiveRisk = plan.decision.effectiveRisk,
                requiredApproval = plan.decision.requiredApproval,
                initialState =
                    if (plan.decision.requiredApproval == ApprovalRequirement.NONE) {
                        ResolutionRunInitialState.READY_FOR_AUTHORIZATION
                    } else {
                        ResolutionRunInitialState.WAITING_FOR_APPROVAL
                    },
            )

        /**
         * Starts a fresh authorized attempt after [predecessor] failed.
         *
         * A retry retains the same case, contract, step, and capability meaning,
         * while [plan] may apply newer evidence or stronger policy safeguards.
         * It receives a new run identity so approvals, authorization, and provider
         * idempotency cannot be reused from the failed attempt.
         *
         * @throws IllegalArgumentException when [plan] changes the operation being
         *   retried or does not use a later-or-equal case evidence boundary.
         */
        fun retry(
            id: ResolutionRunId,
            predecessor: ResolutionRunStart,
            plan: ResolutionRunPlan,
        ): ResolutionRunStart {
            require(plan.caseStreamVersion >= predecessor.caseStreamVersion) {
                "retry cannot use an older case evidence boundary"
            }
            require(plan.contract == predecessor.contract) { "retry must retain the contract revision" }
            require(plan.stepId == predecessor.stepId) { "retry must retain the failed step" }
            require(plan.capability == predecessor.capability) { "retry must retain the failed capability" }
            val base = create(id, predecessor.caseId, plan)
            return base.copy(
                attemptNumber = predecessor.attemptNumber + 1,
                predecessorRunId = predecessor.id,
            )
        }
    }
}
