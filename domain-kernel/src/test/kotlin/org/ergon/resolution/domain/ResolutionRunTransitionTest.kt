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
import java.time.Instant
import java.util.UUID

class ResolutionRunTransitionTest {
    @Test
    fun `successful capability result advances only to verification`() {
        val event = record(CapabilityInvocationOutcome.SUCCEEDED)

        assertThat(event.sequence).isEqualTo(1)
        assertThat(event.type).isEqualTo(ResolutionRunEventType.CAPABILITY_SUCCEEDED)
        assertThat(event.fromState).isEqualTo(ResolutionRunState.WAITING_FOR_APPROVAL)
        assertThat(event.toState).isEqualTo(ResolutionRunState.VERIFYING)
        assertThat(event.occurredAt).isEqualTo(COMPLETED_AT)
    }

    @Test
    fun `failed capability result advances to action failed`() {
        val event = record(CapabilityInvocationOutcome.FAILED)

        assertThat(event.type).isEqualTo(ResolutionRunEventType.CAPABILITY_FAILED)
        assertThat(event.toState).isEqualTo(ResolutionRunState.ACTION_FAILED)
    }

    @Test
    fun `rejects a receipt from another run`() {
        val otherReceipt = receipt(CapabilityInvocationOutcome.SUCCEEDED, ResolutionRunId(UUID.randomUUID()))

        assertThatThrownBy {
            ResolutionRunCapabilityResult.record(
                ResolutionRunEventId(UUID.randomUUID()),
                ResolutionRunCapabilityResultBasis(run(), currentState(), otherReceipt),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("capability receipt belongs to another run")
    }

    @Test
    fun `rejects a second capability result event`() {
        val advancedState = currentState().copy(state = ResolutionRunState.VERIFYING, version = 1)

        assertThatThrownBy {
            ResolutionRunCapabilityResult.record(
                ResolutionRunEventId(UUID.randomUUID()),
                ResolutionRunCapabilityResultBasis(
                    run(),
                    advancedState,
                    receipt(CapabilityInvocationOutcome.SUCCEEDED),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("capability result must be the first run event")
    }

    private fun record(outcome: CapabilityInvocationOutcome): ResolutionRunCapabilityResult =
        ResolutionRunCapabilityResult.record(
            ResolutionRunEventId(UUID.randomUUID()),
            ResolutionRunCapabilityResultBasis(run(), currentState(), receipt(outcome)),
        )

    private fun currentState() =
        ResolutionRunStateSnapshot(
            runId = ResolutionRunId(RUN_ID),
            state = ResolutionRunState.WAITING_FOR_APPROVAL,
            version = 0,
            updatedAt = Instant.parse("2026-09-14T10:00:00Z"),
        )

    private fun run() =
        ResolutionRunStart(
            id = ResolutionRunId(RUN_ID),
            caseId = CaseId(CASE_ID),
            caseStreamVersion = 4,
            contract =
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-workspace-access"),
                    ResolutionContractRevision.of(1),
                ),
            policyRevision = POLICY_REVISION,
            stepId = STEP_ID,
            capability = CAPABILITY,
            effectiveRisk = StepRisk.HIGH,
            requiredApproval = ApprovalRequirement.REQUESTER,
            initialState = ResolutionRunInitialState.WAITING_FOR_APPROVAL,
        )

    private fun receipt(
        outcome: CapabilityInvocationOutcome,
        runId: ResolutionRunId = ResolutionRunId(RUN_ID),
    ): CapabilityInvocationReceipt =
        CapabilityInvocationReceipt.rehydrate(
            CapabilityInvocationReceiptSnapshot(
                authorizationConsumptionId = CapabilityAuthorizationConsumptionId(CONSUMPTION_ID),
                authorizationGrantId = CapabilityAuthorizationGrantId(UUID.randomUUID()),
                runId = runId,
                caseId = CaseId(CASE_ID),
                policyRevision = POLICY_REVISION,
                stepId = STEP_ID,
                capability = CAPABILITY,
                connector = ConnectorName.of("identity-stub"),
                idempotencyKey = CONSUMPTION_ID,
                outcome = outcome,
                providerOperationReference = ProviderOperationReference.of("identity-stub/operations/$CONSUMPTION_ID"),
                consumptionConsumedAt = Instant.parse("2026-09-14T10:01:00Z"),
                completedAt = COMPLETED_AT,
            ),
        )

    companion object {
        private val RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val CASE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val CONSUMPTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val POLICY_REVISION = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1")
        private val STEP_ID = ResolutionStepId.of("unlock-account")
        private val CAPABILITY = CapabilityName.of("identity.account.unlock")
        private val COMPLETED_AT = Instant.parse("2026-09-14T10:02:00Z")
    }
}
