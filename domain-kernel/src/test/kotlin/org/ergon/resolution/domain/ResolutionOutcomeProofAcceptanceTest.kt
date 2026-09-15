package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.ergon.cases.domain.CaseId
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ResolutionOutcomeProofAcceptanceTest {
    @Test
    fun `accepts exact proof as the second and terminal run event`() {
        val accepted =
            ResolutionOutcomeProofAccepted.record(
                EVENT_ID,
                ResolutionOutcomeProofAcceptanceBasis(run(), verifyingState(), assessment(), ACCEPTED_AT),
            )

        assertThat(accepted.sequence).isEqualTo(2)
        assertThat(accepted.type).isEqualTo(ResolutionRunEventType.OUTCOME_PROOF_ACCEPTED)
        assertThat(accepted.fromState).isEqualTo(ResolutionRunState.VERIFYING)
        assertThat(accepted.toState).isEqualTo(ResolutionRunState.VERIFIED_RESOLVED)
        assertThat(accepted.runCaseStreamVersion).isEqualTo(4)
        assertThat(accepted.caseStreamVersion).isEqualTo(6)
        assertThat(accepted.factId).isEqualTo(FACT_ID)
    }

    @Test
    fun `rejects proof unless the run is at verifying version one`() {
        assertThatIllegalArgumentException().isThrownBy {
            ResolutionOutcomeProofAccepted.record(
                EVENT_ID,
                ResolutionOutcomeProofAcceptanceBasis(
                    run(),
                    verifyingState().copy(version = 2),
                    assessment(),
                    ACCEPTED_AT,
                ),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            ResolutionOutcomeProofAccepted.record(
                EVENT_ID,
                ResolutionOutcomeProofAcceptanceBasis(
                    run(),
                    verifyingState().copy(state = ResolutionRunState.ACTION_FAILED),
                    assessment(),
                    ACCEPTED_AT,
                ),
            )
        }
    }

    private fun run() =
        ResolutionRunStart(
            id = RUN_ID,
            caseId = CaseId(UUID.randomUUID()),
            caseStreamVersion = 4,
            contract =
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-workspace-access"),
                    ResolutionContractRevision.of(1),
                ),
            policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
            stepId = ResolutionStepId.of("unlock-account"),
            capability = CapabilityName.of("identity.account.unlock"),
            effectiveRisk = StepRisk.MEDIUM,
            requiredApproval = ApprovalRequirement.REQUESTER,
            initialState = ResolutionRunInitialState.WAITING_FOR_APPROVAL,
        )

    private fun verifyingState(): ResolutionRunStateSnapshot =
        ResolutionRunStateSnapshot(
            RUN_ID,
            ResolutionRunState.VERIFYING,
            1,
            ACCEPTED_AT.minusSeconds(1),
        )

    private fun assessment(): ResolutionOutcomeProofAssessment.Accepted {
        val condition = FactCondition(FACT, VALUE)
        return ResolutionOutcomeProofAssessment.Accepted(
            condition = condition,
            caseStreamVersion = 6,
            evidence =
                ResolutionOutcomeEvidence(
                    factId = FACT_ID,
                    fact = FACT,
                    value = VALUE,
                    observationId = OBSERVATION_ID,
                    observationStreamVersion = 5,
                    factStreamVersion = 6,
                    observedAt = ACCEPTED_AT.minusMillis(2),
                    boundAt = ACCEPTED_AT.minusMillis(1),
                ),
        )
    }

    private companion object {
        val RUN_ID = ResolutionRunId(UUID.randomUUID())
        val EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val FACT_ID = FactId(UUID.randomUUID())
        val OBSERVATION_ID = ObservationId(UUID.randomUUID())
        val FACT = ContractFactType.of("account.access.state")
        val VALUE = ContractFactValue.of("ACTIVE")
        val ACCEPTED_AT: Instant = Instant.parse("2026-09-15T12:00:00Z")
    }
}
