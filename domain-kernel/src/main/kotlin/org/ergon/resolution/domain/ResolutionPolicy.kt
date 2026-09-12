package org.ergon.resolution.domain

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.StepRisk

private const val MAX_POLICY_REVISION_LENGTH = 200
private const val POLICY_REVISION_PATTERN = "[a-z][a-z0-9.-]*(/[a-z0-9][a-z0-9.-]*)+"

/** Immutable identity of the policy rules used for a planning decision. */
@JvmInline
value class ResolutionPolicyRevision private constructor(
    val value: String,
) {
    companion object {
        /**
         * Creates a revision identifier without normalizing it.
         *
         * @throws IllegalArgumentException when [value] is longer than 200
         *   characters or is not a lowercase, path-like identifier.
         */
        fun of(value: String): ResolutionPolicyRevision {
            require(value.length <= MAX_POLICY_REVISION_LENGTH && value.matches(Regex(POLICY_REVISION_PATTERN))) {
                "policy revision must be a path-like identifier of at most $MAX_POLICY_REVISION_LENGTH characters"
            }
            return ResolutionPolicyRevision(value)
        }
    }
}

/**
 * Minimum safeguards imposed on one capability by a policy revision.
 *
 * @throws IllegalArgumentException when a high-risk rule requires no human approval.
 */
data class CapabilityPolicyRule(
    val capability: CapabilityName,
    val minimumRisk: StepRisk,
    val minimumApproval: ApprovalRequirement,
) {
    init {
        require(minimumRisk != StepRisk.HIGH || minimumApproval != ApprovalRequirement.NONE) {
            "high-risk capability policy requires requester or resolver approval"
        }
    }
}

/** Stable reasons for refusing to plan a contract step. */
enum class StepPolicyDenialReason {
    CAPABILITY_NOT_ALLOWED,
}

/**
 * Policy evaluation result for one step.
 *
 * [Requirements] describes prerequisites and does not authorize execution;
 * actor authority must still be checked when satisfying an approval.
 */
sealed interface StepPolicyDecision {
    data class Requirements(
        val effectiveRisk: StepRisk,
        val requiredApproval: ApprovalRequirement,
    ) : StepPolicyDecision {
        init {
            require(effectiveRisk != StepRisk.HIGH || requiredApproval != ApprovalRequirement.NONE) {
                "high-risk policy decision requires requester or resolver approval"
            }
        }
    }

    data class Denied(
        val reason: StepPolicyDenialReason,
    ) : StepPolicyDecision
}

/** Deterministic, immutable capability rules identified by one exact [revision]. */
class ResolutionPolicy private constructor(
    val revision: ResolutionPolicyRevision,
    rules: List<CapabilityPolicyRule>,
) {
    private val rulesByCapability = rules.associateBy(CapabilityPolicyRule::capability)

    /**
     * Determines the safeguards required for [step].
     *
     * A missing rule denies the capability. A matching rule can strengthen but
     * never reduce the risk or approval declared by the contract.
     */
    fun evaluate(step: ResolutionStep): StepPolicyDecision {
        val rule =
            rulesByCapability[step.capability]
                ?: return StepPolicyDecision.Denied(StepPolicyDenialReason.CAPABILITY_NOT_ALLOWED)
        return StepPolicyDecision.Requirements(
            effectiveRisk = stricterRisk(step.risk, rule.minimumRisk),
            requiredApproval = stricterApproval(step.approval, rule.minimumApproval),
        )
    }

    companion object {
        /**
         * Defines a policy with exactly one rule per capability.
         *
         * @throws IllegalArgumentException when [rules] is empty or repeats a capability.
         */
        fun define(
            revision: ResolutionPolicyRevision,
            rules: List<CapabilityPolicyRule>,
        ): ResolutionPolicy {
            require(rules.isNotEmpty()) { "resolution policy must contain at least one capability rule" }
            require(rules.map(CapabilityPolicyRule::capability).distinct().size == rules.size) {
                "resolution policy must not contain duplicate capability rules"
            }
            return ResolutionPolicy(revision, rules.toList())
        }
    }
}

private fun stricterRisk(
    declared: StepRisk,
    minimum: StepRisk,
): StepRisk = if (riskRank(declared) >= riskRank(minimum)) declared else minimum

private fun riskRank(risk: StepRisk): Int =
    when (risk) {
        StepRisk.LOW -> 0
        StepRisk.MEDIUM -> 1
        StepRisk.HIGH -> 2
    }

private fun stricterApproval(
    declared: ApprovalRequirement,
    minimum: ApprovalRequirement,
): ApprovalRequirement = if (approvalRank(declared) >= approvalRank(minimum)) declared else minimum

private fun approvalRank(approval: ApprovalRequirement): Int =
    when (approval) {
        ApprovalRequirement.NONE -> 0
        ApprovalRequirement.REQUESTER -> 1
        ApprovalRequirement.RESOLVER -> 2
    }
