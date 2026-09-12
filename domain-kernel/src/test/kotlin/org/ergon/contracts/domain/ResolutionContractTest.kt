package org.ergon.contracts.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ResolutionContractTest {
    @Test
    fun `defines ordered access restoration contract`() {
        val contract = accessRestorationContract()

        assertThat(contract.key.value).isEqualTo("restore-workspace-access")
        assertThat(contract.revision.value).isEqualTo(1)
        assertThat(contract.requiredEvidence.map(ContractFactType::value))
            .containsExactly("account.access.state")
        assertThat(contract.steps.map(ResolutionStep::id)).containsExactly(STEP_ID)
        assertThat(contract.outcomeProof.expectedValue.value).isEqualTo("ACTIVE")
    }

    @Test
    fun `requires applicability evidence and unique declarations`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                accessRestorationContract(requiredEvidence = listOf(ContractFactType.of("account.entitlement")))
            }.withMessageContaining("applicability fact")

        assertThatIllegalArgumentException()
            .isThrownBy {
                accessRestorationContract(requiredEvidence = listOf(ACCOUNT_STATE, ACCOUNT_STATE))
            }.withMessageContaining("duplicates")

        assertThatIllegalArgumentException()
            .isThrownBy {
                accessRestorationContract(steps = listOf(STEP, STEP))
            }.withMessageContaining("step ids")
    }

    @Test
    fun `bounds required evidence and steps`() {
        assertThatIllegalArgumentException()
            .isThrownBy { accessRestorationContract(requiredEvidence = emptyList()) }
            .withMessageContaining("must not be empty")

        val excessiveEvidence =
            listOf(ACCOUNT_STATE) +
                (1..ResolutionContract.MAX_REQUIRED_EVIDENCE).map { index ->
                    ContractFactType.of("account.evidence.value$index")
                }
        assertThatIllegalArgumentException()
            .isThrownBy { accessRestorationContract(requiredEvidence = excessiveEvidence) }
            .withMessageContaining("must not exceed")

        assertThatIllegalArgumentException()
            .isThrownBy { accessRestorationContract(steps = emptyList()) }
            .withMessageContaining("must not be empty")

        val excessiveSteps =
            (0..ResolutionContract.MAX_STEPS).map { index ->
                STEP.copy(id = ResolutionStepId.of("unlock-account-$index"))
            }
        assertThatIllegalArgumentException()
            .isThrownBy { accessRestorationContract(steps = excessiveSteps) }
            .withMessageContaining("must not exceed")
    }

    @Test
    fun `requires human approval for high risk step`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ResolutionStep(
                    id = STEP_ID,
                    capability = CapabilityName.of("identity.account.unlock"),
                    risk = StepRisk.HIGH,
                    approval = ApprovalRequirement.NONE,
                )
            }.withMessageContaining("high-risk")
    }

    @Test
    fun `rejects ambiguous contract identifiers`() {
        assertThatIllegalArgumentException().isThrownBy { ResolutionContractKey.of("Restore Access") }
        assertThatIllegalArgumentException().isThrownBy { ResolutionContractRevision.of(0) }
        assertThatIllegalArgumentException().isThrownBy { ContractFactType.of("state") }
        assertThatIllegalArgumentException().isThrownBy { CapabilityName.of("UnlockAccount") }
    }

    private fun accessRestorationContract(
        requiredEvidence: List<ContractFactType> = listOf(ACCOUNT_STATE),
        steps: List<ResolutionStep> = listOf(STEP),
    ) = ResolutionContract.define(
        identity =
            ResolutionContractIdentity(
                key = ResolutionContractKey.of("restore-workspace-access"),
                revision = ResolutionContractRevision.of(1),
            ),
        applicability = FactCondition(ACCOUNT_STATE, ContractFactValue.of("LOCKED")),
        requiredEvidence = requiredEvidence,
        steps = steps,
        outcomeProof = FactCondition(ACCOUNT_STATE, ContractFactValue.of("ACTIVE")),
    )

    private companion object {
        val ACCOUNT_STATE = ContractFactType.of("account.access.state")
        val STEP_ID = ResolutionStepId.of("unlock-account")
        val STEP =
            ResolutionStep(
                id = STEP_ID,
                capability = CapabilityName.of("identity.account.unlock"),
                risk = StepRisk.HIGH,
                approval = ApprovalRequirement.REQUESTER,
            )
    }
}
