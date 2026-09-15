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
import org.ergon.identity.domain.HumanActorId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ResolutionRunRetryTest {
    @Test
    fun `retry creates a distinct next attempt with fresh safeguards`() {
        val replacement = ResolutionRunStart.retry(REPLACEMENT_RUN_ID, failedRun(), plan())

        assertThat(replacement.attemptNumber).isEqualTo(2)
        assertThat(replacement.predecessorRunId).isEqualTo(FAILED_RUN_ID)
        assertThat(replacement.policyRevision).isEqualTo(REVISED_POLICY)

        val event =
            ResolutionRunRetryStarted.start(
                EVENT_ID,
                ResolutionRunRetryBasis(failedRun(), failedState(), replacement, resolverEvidence()),
                RETRIED_AT,
            )
        assertThat(event.sequence).isEqualTo(2)
        assertThat(event.fromState).isEqualTo(ResolutionRunState.ACTION_FAILED)
        assertThat(event.toState).isEqualTo(ResolutionRunState.SUPERSEDED)
        assertThat(event.replacementRunId).isEqualTo(REPLACEMENT_RUN_ID)
        assertThat(event.authorization)
            .isEqualTo(ResolutionRunRetryAuthorization(RESOLVER_ACTOR_ID, RESOLVER_EVIDENCE_ID))
    }

    @Test
    fun `retry rejects changed operation meaning`() {
        assertThatThrownBy {
            ResolutionRunStart.retry(
                REPLACEMENT_RUN_ID,
                failedRun(),
                plan().copy(capability = CapabilityName.of("identity.account.disable")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("retry must retain the failed capability")
    }

    @Test
    fun `retry event rejects a run that has not failed`() {
        val replacement = ResolutionRunStart.retry(REPLACEMENT_RUN_ID, failedRun(), plan())

        assertThatThrownBy {
            ResolutionRunRetryStarted.start(
                EVENT_ID,
                ResolutionRunRetryBasis(
                    failedRun(),
                    failedState().copy(state = ResolutionRunState.VERIFYING),
                    replacement,
                    resolverEvidence(),
                ),
                RETRIED_AT,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("retry requires an action-failed run at version one")
    }

    private fun failedRun() =
        ResolutionRunStart(
            id = FAILED_RUN_ID,
            caseId = CaseId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
            caseStreamVersion = 4,
            contract = CONTRACT,
            policyRevision = ORIGINAL_POLICY,
            stepId = STEP,
            capability = CAPABILITY,
            effectiveRisk = StepRisk.HIGH,
            requiredApproval = ApprovalRequirement.REQUESTER,
            initialState = ResolutionRunInitialState.WAITING_FOR_APPROVAL,
        )

    private fun plan() =
        ResolutionRunPlan(
            caseStreamVersion = 4,
            contract = CONTRACT,
            policyRevision = REVISED_POLICY,
            stepId = STEP,
            capability = CAPABILITY,
            decision = StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.RESOLVER),
        )

    private fun failedState() =
        ResolutionRunStateSnapshot(
            FAILED_RUN_ID,
            ResolutionRunState.ACTION_FAILED,
            1,
            Instant.parse("2026-09-15T10:00:00Z"),
        )

    private fun resolverEvidence() =
        ApprovalAuthorityEvidence(
            id = RESOLVER_EVIDENCE_ID,
            actorId = RESOLVER_ACTOR_ID,
            authority = ApprovalAuthority.RESOLVER,
            caseId = null,
            source = ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
            attestedAt = Instant.parse("2026-09-15T09:00:00Z"),
            expiresAt = Instant.parse("2026-09-15T11:00:00Z"),
        )

    private companion object {
        val FAILED_RUN_ID = ResolutionRunId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val REPLACEMENT_RUN_ID = ResolutionRunId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        val EVENT_ID = ResolutionRunEventId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
        val RESOLVER_ACTOR_ID = HumanActorId(UUID.fromString("55555555-5555-5555-5555-555555555555"))
        val RESOLVER_EVIDENCE_ID =
            ApprovalAuthorityEvidenceId(UUID.fromString("66666666-6666-6666-6666-666666666666"))
        val CONTRACT =
            ResolutionContractIdentity(
                ResolutionContractKey.of("restore-workspace-access"),
                ResolutionContractRevision.of(1),
            )
        val ORIGINAL_POLICY = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1")
        val REVISED_POLICY = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v2")
        val STEP = ResolutionStepId.of("unlock-account")
        val CAPABILITY = CapabilityName.of("identity.account.unlock")
        val RETRIED_AT = Instant.parse("2026-09-15T10:01:00Z")
    }
}
