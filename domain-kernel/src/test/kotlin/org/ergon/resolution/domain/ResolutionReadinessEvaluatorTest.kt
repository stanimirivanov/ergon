package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContract
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStep
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.junit.jupiter.api.Test

class ResolutionReadinessEvaluatorTest {
    @Test
    fun `reports every missing required fact before evaluating applicability`() {
        val result = ResolutionReadinessEvaluator.evaluate(CONTRACT, ResolutionEvidence.of(emptyMap()))

        assertThat(result)
            .isEqualTo(ResolutionReadiness.MissingEvidence.of(listOf(ACCOUNT_ACCESS_STATE, ACCOUNT_ENTITLEMENT)))
    }

    @Test
    fun `reports non-applicability with expected and actual values`() {
        val actual = ContractFactValue.of("ACTIVE")
        val result =
            ResolutionReadinessEvaluator.evaluate(
                CONTRACT,
                evidence(accountState = actual),
            )

        assertThat(result).isEqualTo(ResolutionReadiness.NotApplicable(CONTRACT.applicability, actual))
    }

    @Test
    fun `is ready only when required evidence exists and applicability matches`() {
        val actual = ContractFactValue.of("LOCKED")
        val result =
            ResolutionReadinessEvaluator.evaluate(
                CONTRACT,
                evidence(accountState = actual),
            )

        assertThat(result).isEqualTo(ResolutionReadiness.Ready(CONTRACT.applicability, actual))
    }

    private fun evidence(accountState: ContractFactValue) =
        ResolutionEvidence.of(
            mapOf(
                ACCOUNT_ACCESS_STATE to accountState,
                ACCOUNT_ENTITLEMENT to ContractFactValue.of("MEMBER"),
            ),
        )

    companion object {
        private val ACCOUNT_ACCESS_STATE = ContractFactType.of("account.access.state")
        private val ACCOUNT_ENTITLEMENT = ContractFactType.of("account.entitlement.level")
        private val CONTRACT =
            ResolutionContract.define(
                identity =
                    ResolutionContractIdentity(
                        key = ResolutionContractKey.of("restore-workspace-access"),
                        revision = ResolutionContractRevision.of(1),
                    ),
                applicability = FactCondition(ACCOUNT_ACCESS_STATE, ContractFactValue.of("LOCKED")),
                requiredEvidence = listOf(ACCOUNT_ACCESS_STATE, ACCOUNT_ENTITLEMENT),
                steps =
                    listOf(
                        ResolutionStep(
                            id = ResolutionStepId.of("unlock-account"),
                            capability = CapabilityName.of("identity.account.unlock"),
                            risk = StepRisk.HIGH,
                            approval = ApprovalRequirement.REQUESTER,
                        ),
                    ),
                outcomeProof = FactCondition(ACCOUNT_ACCESS_STATE, ContractFactValue.of("ACTIVE")),
            )
    }
}
