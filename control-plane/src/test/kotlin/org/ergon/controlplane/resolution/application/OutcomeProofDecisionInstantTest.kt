package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
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
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunInitialState
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OutcomeProofDecisionInstantTest {
    @Test
    fun `explicit authority instant survives completion without resampling or rounding case time`() {
        val tenant = TenantId(UUID.randomUUID())
        val run = run()
        val state = ResolutionRunStateSnapshot(run.id, ResolutionRunState.VERIFYING, 1, AT.minusSeconds(1))
        val condition = FactCondition(ContractFactType.of("account.access.state"), ContractFactValue.of("ACTIVE"))
        val proof =
            ResolutionOutcomeProofAssessment.Accepted(
                condition,
                6,
                ResolutionOutcomeEvidence(
                    FactId(UUID.randomUUID()),
                    condition.fact,
                    condition.expectedValue,
                    ObservationId(UUID.randomUUID()),
                    5,
                    6,
                    AT.minusSeconds(1),
                    AT.minusNanos(1),
                ),
            )
        val factoryClock = Clock.fixed(AT.plusSeconds(60), ZoneOffset.UTC)
        val factory =
            ResolutionOutcomeProofAcceptedEventFactory(
                { ResolutionRunEventId(UUID.randomUUID()) },
                factoryClock,
            )
        val closer = Mockito.mock(ResolutionOutcomeCaseCloser::class.java)
        val event = ResolutionOutcomeProofCompletion(factory, closer).stageAt(tenant, run, state, proof, AT)
        assertThat(event.acceptedAt).isEqualTo(AT)
        Mockito.verify(closer).close(tenant, run.caseId, event)
        // Internal acceptance keeps its original injected-clock behavior.
        assertThat(factory.create(run, state, proof).acceptedAt).isEqualTo(factoryClock.instant())
    }

    private fun run() =
        ResolutionRunStart(
            id = ResolutionRunId(UUID.randomUUID()),
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

    companion object {
        private val AT = Instant.parse("2026-10-15T08:00:00.123456789Z")
    }
}
