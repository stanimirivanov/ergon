package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.junit.jupiter.api.Test

class ResolutionPolicyTest {
    @Test
    fun `policy strengthens risk and approval declared by a contract`() {
        val decision = POLICY.evaluate(step(StepRisk.LOW, ApprovalRequirement.NONE))

        assertThat(decision)
            .isEqualTo(StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.REQUESTER))
    }

    @Test
    fun `policy preserves stricter resolver approval declared by a contract`() {
        val decision = POLICY.evaluate(step(StepRisk.HIGH, ApprovalRequirement.RESOLVER))

        assertThat(decision)
            .isEqualTo(StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.RESOLVER))
    }

    @Test
    fun `policy denies capabilities without an explicit rule`() {
        val decision =
            POLICY.evaluate(
                ResolutionStep(
                    id = ResolutionStepId.of("disable-account"),
                    capability = CapabilityName.of("identity.account.disable"),
                    risk = StepRisk.HIGH,
                    approval = ApprovalRequirement.RESOLVER,
                ),
            )

        assertThat(decision)
            .isEqualTo(StepPolicyDecision.Denied(StepPolicyDenialReason.CAPABILITY_NOT_ALLOWED))
    }

    @Test
    fun `policy rejects duplicate capability rules`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ResolutionPolicy.define(POLICY_REVISION, listOf(UNLOCK_RULE, UNLOCK_RULE))
            }.withMessageContaining("duplicate")
    }

    @Test
    fun `policy revision rejects unstable free form identifiers`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ResolutionPolicyRevision.of("access restoration latest") }
            .withMessageContaining("path-like")
    }

    @Test
    fun `policy rejects high risk rules without human approval`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                CapabilityPolicyRule(
                    capability = UNLOCK_CAPABILITY,
                    minimumRisk = StepRisk.HIGH,
                    minimumApproval = ApprovalRequirement.NONE,
                )
            }.withMessageContaining("high-risk")
    }

    private fun step(
        risk: StepRisk,
        approval: ApprovalRequirement,
    ) = ResolutionStep(ResolutionStepId.of("unlock-account"), UNLOCK_CAPABILITY, risk, approval)

    companion object {
        private val POLICY_REVISION = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1")
        private val UNLOCK_CAPABILITY = CapabilityName.of("identity.account.unlock")
        private val UNLOCK_RULE =
            CapabilityPolicyRule(
                capability = UNLOCK_CAPABILITY,
                minimumRisk = StepRisk.HIGH,
                minimumApproval = ApprovalRequirement.REQUESTER,
            )
        private val POLICY = ResolutionPolicy.define(POLICY_REVISION, listOf(UNLOCK_RULE))
    }
}
