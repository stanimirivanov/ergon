package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.junit.jupiter.api.Test
import java.util.UUID

class ResolutionRunStartTest {
    @Test
    fun `human approval requirement starts a run waiting for approval`() {
        val run = runStart(ApprovalRequirement.REQUESTER)

        assertThat(run.initialState).isEqualTo(ResolutionRunInitialState.WAITING_FOR_APPROVAL)
    }

    @Test
    fun `no human approval starts a run ready only for authorization`() {
        val run = runStart(ApprovalRequirement.NONE)

        assertThat(run.initialState).isEqualTo(ResolutionRunInitialState.READY_FOR_AUTHORIZATION)
    }

    @Test
    fun `run rejects a nonpositive evidence boundary`() {
        assertThatThrownBy { runStart(ApprovalRequirement.REQUESTER, caseStreamVersion = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("run case stream version must be positive")
    }

    private fun runStart(
        approval: ApprovalRequirement,
        caseStreamVersion: Long = 4,
    ): ResolutionRunStart =
        ResolutionRunStart.create(
            id = ResolutionRunId(UUID.randomUUID()),
            caseId = CaseId(UUID.randomUUID()),
            plan =
                ResolutionRunPlan(
                    caseStreamVersion = caseStreamVersion,
                    contract =
                        ResolutionContractIdentity(
                            ResolutionContractKey.of("restore-workspace-access"),
                            ResolutionContractRevision.of(1),
                        ),
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                    stepId = ResolutionStepId.of("unlock-account"),
                    capability = CapabilityName.of("identity.account.unlock"),
                    decision = StepPolicyDecision.Requirements(StepRisk.MEDIUM, approval),
                ),
        )
}
