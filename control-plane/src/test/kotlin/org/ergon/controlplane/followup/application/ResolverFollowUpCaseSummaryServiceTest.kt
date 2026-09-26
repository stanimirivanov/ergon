package org.ergon.controlplane.followup.application

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
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.PinnedResolutionContract
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.controlplane.resolution.application.StoredResolutionRunEscalation
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpSource
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.CapabilityAuthorizationConsumptionId
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.ergon.resolution.domain.CapabilityInvocationOutcome
import org.ergon.resolution.domain.CapabilityInvocationReceipt
import org.ergon.resolution.domain.CapabilityInvocationReceiptSnapshot
import org.ergon.resolution.domain.ConnectorName
import org.ergon.resolution.domain.ProviderOperationReference
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRetryDenialReason
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationAuthorization
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEscalationRequested
import org.ergon.resolution.domain.ResolutionRunEscalationSnapshot
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ResolverFollowUpCaseSummaryServiceTest {
    private val claims = mock(HumanFollowUpClaimRepository::class.java)
    private val cases = mock(CaseTimelineRepository::class.java)
    private val runs = mock(ResolutionRunRepository::class.java)
    private val transitions = mock(ResolutionRunTransitionRepository::class.java)
    private val receipts = mock(CapabilityInvocationReceiptRepository::class.java)
    private val escalations = mock(ResolutionRunEscalationRepository::class.java)
    private val transactions = RecordingTransactionRunner()
    private val service =
        ResolverFollowUpCaseSummaryService(
            claims,
            cases,
            runs,
            transitions,
            receipts,
            escalations,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    @Test
    fun `returns failed execution and escalation context after proving current ownership`() {
        val ownedWork = ownedWork()
        val timeline = timeline()
        val run = storedRun()
        val state = ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ESCALATED, 2, ESCALATED_AT)
        val executionReceipt = storedReceipt()
        val escalation = storedEscalation()
        `when`(claims.findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(ownedWork)
        `when`(cases.find(TENANT_ID, CASE_ID)).thenReturn(timeline)
        `when`(runs.find(TENANT_ID, RUN_ID)).thenReturn(run)
        `when`(transitions.findState(TENANT_ID, RUN_ID)).thenReturn(state)
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(executionReceipt)
        `when`(escalations.find(TENANT_ID, RUN_ID)).thenReturn(escalation)

        val result = service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result).isEqualTo(
            ResolverFollowUpCaseSummary(ownedWork, timeline, run, state, executionReceipt, escalation),
        )
        assertThat(transactions.calls).isEqualTo(1)
        inOrder(claims, cases, runs, transitions, receipts, escalations).apply {
            verify(claims).findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)
            verify(cases).find(TENANT_ID, CASE_ID)
            verify(runs).find(TENANT_ID, RUN_ID)
            verify(transitions).findState(TENANT_ID, RUN_ID)
            verify(receipts).findByRun(TENANT_ID, RUN_ID)
            verify(escalations).find(TENANT_ID, RUN_ID)
        }
    }

    @Test
    fun `does not read case or run records when ownership is hidden`() {
        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(ResolverFollowUpCaseSummaryNotFoundException::class.java)

        verifyNoInteractions(cases, runs, transitions, receipts, escalations)
        assertThat(transactions.calls).isEqualTo(1)
    }

    @Test
    fun `rejects durable context whose run is no longer escalated`() {
        `when`(claims.findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(ownedWork())
        `when`(cases.find(TENANT_ID, CASE_ID)).thenReturn(timeline())
        `when`(runs.find(TENANT_ID, RUN_ID)).thenReturn(storedRun())
        `when`(transitions.findState(TENANT_ID, RUN_ID)).thenReturn(
            ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ACTION_FAILED, 1, NOW),
        )

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up resolution run is not escalated")

        verifyNoInteractions(receipts, escalations)
    }

    @Test
    fun `rejects an escalated handoff backed by a successful connector receipt`() {
        prepareConsistentBase()
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(
            storedReceipt(CapabilityInvocationOutcome.SUCCEEDED),
        )
        `when`(escalations.find(TENANT_ID, RUN_ID)).thenReturn(storedEscalation())

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up capability receipt is not failed")
    }

    @Test
    fun `rejects an escalation event that did not open the owned work`() {
        prepareConsistentBase()
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(storedReceipt())
        `when`(escalations.find(TENANT_ID, RUN_ID)).thenReturn(
            storedEscalation(ResolutionRunEventId(UUID.randomUUID())),
        )

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up escalation event identity is inconsistent")
    }

    private fun prepareConsistentBase() {
        `when`(claims.findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(ownedWork())
        `when`(cases.find(TENANT_ID, CASE_ID)).thenReturn(timeline())
        `when`(runs.find(TENANT_ID, RUN_ID)).thenReturn(storedRun())
        `when`(transitions.findState(TENANT_ID, RUN_ID)).thenReturn(
            ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ESCALATED, 2, ESCALATED_AT),
        )
    }

    private fun ownedWork(): ResolverOwnedHumanFollowUpWork {
        val evidence =
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                ACTOR_ID,
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
            )
        val item =
            HumanFollowUpWorkItem.open(
                WORK_ITEM_ID,
                HumanFollowUpSource(CASE_ID, RUN_ID, ESCALATION_EVENT_ID, REASON),
                HumanFollowUpQueueKey.ACCESS_RESTORATION,
                ESCALATED_AT,
            )
        val claim =
            HumanFollowUpClaim.claim(
                HumanFollowUpClaimId(UUID.randomUUID()),
                WORK_ITEM_ID,
                evidence,
                NOW.minusSeconds(20),
            )
        return ResolverOwnedHumanFollowUpWork(
            StoredHumanFollowUpWorkItem(item, NOW.minusSeconds(29)),
            StoredHumanFollowUpClaim(claim, NOW.minusSeconds(19)),
        )
    }

    private fun timeline() =
        CaseTimeline(
            CASE_ID.value,
            "Restore workspace access",
            "OPEN",
            4,
            PinnedResolutionContract(
                "restore-workspace-access",
                1,
                3,
                NOW.minusSeconds(150),
                NOW.minusSeconds(149),
            ),
            emptyList(),
        )

    private fun storedRun(): StoredResolutionRunStart {
        val plan =
            ResolutionRunPlan(
                4,
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-workspace-access"),
                    ResolutionContractRevision.of(1),
                ),
                ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                ResolutionStepId.of("unlock-account"),
                CapabilityName.of("identity.account.unlock"),
                StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.RESOLVER),
            )
        return StoredResolutionRunStart(ResolutionRunStart.create(RUN_ID, CASE_ID, plan), NOW.minusSeconds(120))
    }

    private fun storedReceipt(
        outcome: CapabilityInvocationOutcome = CapabilityInvocationOutcome.FAILED,
    ): StoredCapabilityInvocationReceipt {
        val consumptionId = CapabilityAuthorizationConsumptionId(CONSUMPTION_ID)
        val receipt =
            CapabilityInvocationReceipt.rehydrate(
                CapabilityInvocationReceiptSnapshot(
                    authorizationConsumptionId = consumptionId,
                    authorizationGrantId = CapabilityAuthorizationGrantId(UUID.randomUUID()),
                    runId = RUN_ID,
                    caseId = CASE_ID,
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                    stepId = ResolutionStepId.of("unlock-account"),
                    capability = CapabilityName.of("identity.account.unlock"),
                    connector = ConnectorName.of("identity-stub"),
                    idempotencyKey = consumptionId.value,
                    outcome = outcome,
                    providerOperationReference = ProviderOperationReference.of("identity-stub/operations/failed"),
                    consumptionConsumedAt = NOW.minusSeconds(60),
                    completedAt = NOW.minusSeconds(50),
                ),
            )
        return StoredCapabilityInvocationReceipt(receipt, NOW.minusSeconds(49))
    }

    private fun storedEscalation(eventId: ResolutionRunEventId = ESCALATION_EVENT_ID): StoredResolutionRunEscalation {
        val event =
            ResolutionRunEscalationRequested.rehydrate(
                ResolutionRunEscalationSnapshot(
                    id = eventId,
                    runId = RUN_ID,
                    sequence = 2,
                    type = ResolutionRunEventType.ESCALATION_REQUESTED,
                    fromState = ResolutionRunState.ACTION_FAILED,
                    toState = ResolutionRunState.ESCALATED,
                    reason = REASON,
                    authorization =
                        ResolutionRunEscalationAuthorization(
                            ACTOR_ID,
                            ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                        ),
                    retryDenial =
                        ResolutionRetryEligibility.Denied(
                            ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v1"),
                            1,
                            1,
                            ResolutionRetryDenialReason.ATTEMPT_LIMIT_REACHED,
                        ),
                    occurredAt = ESCALATED_AT,
                ),
            )
        return StoredResolutionRunEscalation(event, ESCALATED_AT.plusSeconds(1))
    }

    private class RecordingTransactionRunner : TransactionRunner {
        var calls = 0

        override fun <T : Any> required(block: () -> T): T {
            calls += 1
            return block()
        }
    }

    private companion object {
        val TENANT_ID = TenantId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val CASE_ID = CaseId(UUID.randomUUID())
        val RUN_ID = ResolutionRunId(UUID.randomUUID())
        val ESCALATION_EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val REASON = ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED
        val NOW = Instant.parse("2026-09-25T12:00:00Z")
        val ESCALATED_AT = NOW.minusSeconds(30)
        val CONSUMPTION_ID = UUID.randomUUID()
    }
}
