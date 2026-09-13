package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionReadiness
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.StepPolicyDecision
import org.ergon.resolution.domain.StepPolicyDenialReason
import org.junit.jupiter.api.Test
import java.util.UUID

class ResolutionRunServiceTest {
    @Test
    fun `policy denial prevents run identity allocation and persistence`() {
        val tenantId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val contract = contract()
        val plan =
            CaseResolutionPlan(
                readiness =
                    CaseReadiness.Evaluated(
                        caseId = caseId,
                        caseStreamVersion = 4,
                        contract = contract,
                        readiness = ResolutionReadiness.Ready(contract.applicability, LOCKED),
                    ),
                policyRevision = POLICY_REVISION,
                nextStep =
                    PlannedResolutionStep(
                        contract.steps.single(),
                        StepPolicyDecision.Denied(StepPolicyDenialReason.CAPABILITY_NOT_ALLOWED),
                    ),
            )
        val service =
            ResolutionRunService(
                planningService = ResolutionPlanner { _, _ -> plan },
                repository = NeverCalledRunRepository,
                identities = ResolutionRunIdentityGenerator { error("identity must not be allocated") },
                transactionRunner = ImmediateTransactionRunner,
            )

        assertThatThrownBy { service.start(tenantId, caseId, expectedCaseVersion = 4) }
            .isInstanceOf(ResolutionRunPolicyDeniedException::class.java)
            .hasMessageContaining("CAPABILITY_NOT_ALLOWED")
    }

    private fun contract(): ResolutionContract =
        ResolutionContract.define(
            identity =
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-workspace-access"),
                    ResolutionContractRevision.of(1),
                ),
            applicability = FactCondition(ACCOUNT_ACCESS_STATE, LOCKED),
            requiredEvidence = listOf(ACCOUNT_ACCESS_STATE),
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

    companion object {
        private val ACCOUNT_ACCESS_STATE = ContractFactType.of("account.access.state")
        private val LOCKED = ContractFactValue.of("LOCKED")
        private val POLICY_REVISION = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1")
    }
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T : Any> required(block: () -> T): T = block()
}

private object NeverCalledRunRepository : ResolutionRunRepository {
    override fun create(
        tenantId: TenantId,
        run: ResolutionRunStart,
    ): StoredResolutionRunStart = error("run must not be persisted")

    override fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunStart? = error("run must not be loaded")
}
