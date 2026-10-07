package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
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
import org.ergon.controlplane.cases.application.CaseTimeline
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.PinnedResolutionContract
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.contracts.application.StoredResolutionContractRevision
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.StoredCapabilityInvocationReceipt
import org.ergon.controlplane.resolution.application.StoredResolutionRunCapabilityResult
import org.ergon.controlplane.resolution.application.StoredResolutionRunEscalation
import org.ergon.controlplane.resolution.application.StoredResolutionRunRetry
import org.ergon.controlplane.resolution.application.StoredResolutionRunStart
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
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
import org.ergon.resolution.domain.ResolutionRunCapabilityResult
import org.ergon.resolution.domain.ResolutionRunCapabilityResultSnapshot
import org.ergon.resolution.domain.ResolutionRunEscalationAuthorization
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEscalationRequested
import org.ergon.resolution.domain.ResolutionRunEscalationSnapshot
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunEventType
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunRetrySnapshot
import org.ergon.resolution.domain.ResolutionRunRetryStarted
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
    private val retries = mock(ResolutionRunRetryRepository::class.java)
    private val contracts = mock(ResolutionContractRevisionRepository::class.java)
    private val transactions = RecordingTransactionRunner()
    private val service =
        ResolverFollowUpCaseSummaryService(
            claims,
            cases,
            runs,
            transitions,
            receipts,
            escalations,
            retries,
            contracts,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    @Test
    fun `returns failed attempt and unassessed pinned proof after proving ownership`() {
        val ownedWork = ownedWork()
        val timeline = timeline()
        val run = storedRun()
        val state = ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ESCALATED, 2, ESCALATED_AT)
        val executionReceipt = storedReceipt()
        val escalation = storedEscalation()
        val capabilityResult = storedResult()
        val contract = storedContract()
        `when`(claims.findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(ownedWork)
        `when`(cases.find(TENANT_ID, CASE_ID)).thenReturn(timeline)
        `when`(runs.find(TENANT_ID, RUN_ID)).thenReturn(run)
        `when`(transitions.findState(TENANT_ID, RUN_ID)).thenReturn(state)
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(executionReceipt)
        `when`(escalations.find(TENANT_ID, RUN_ID)).thenReturn(escalation)
        `when`(transitions.findByReceipt(TENANT_ID, executionReceipt.receipt.authorizationConsumptionId))
            .thenReturn(capabilityResult)
        `when`(contracts.find(TENANT_ID, run.run.contract.key, run.run.contract.revision)).thenReturn(contract)

        val result = service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result).isEqualTo(
            ResolverFollowUpCaseSummary(
                ownedWork,
                timeline,
                run,
                state,
                executionReceipt,
                escalation,
                listOf(ResolverFollowUpRunAttempt(run, state, executionReceipt, capabilityResult, null)),
                contract.contract.outcomeProof,
            ),
        )
        assertThat(transactions.calls).isEqualTo(1)
        inOrder(claims, cases, runs, transitions, receipts, escalations, contracts).apply {
            verify(claims).findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)
            verify(cases).find(TENANT_ID, CASE_ID)
            verify(runs).find(TENANT_ID, RUN_ID)
            verify(transitions).findState(TENANT_ID, RUN_ID)
            verify(receipts).findByRun(TENANT_ID, RUN_ID)
            verify(escalations).find(TENANT_ID, RUN_ID)
            verify(transitions).findByReceipt(TENANT_ID, executionReceipt.receipt.authorizationConsumptionId)
            verify(contracts).find(TENANT_ID, run.run.contract.key, run.run.contract.revision)
        }
    }

    @Test
    fun `returns predecessor then escalation attempt with explicit retry link`() {
        prepareTwoAttemptContext()

        val result = service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result.runHistory.map { it.run.run.id }).containsExactly(PREDECESSOR_RUN_ID, RUN_ID)
        assertThat(result.runHistory.map { it.state.state })
            .containsExactly(ResolutionRunState.SUPERSEDED, ResolutionRunState.ESCALATED)
        assertThat(
            result.runHistory
                .first()
                .retry
                ?.event
                ?.replacementRunId,
        ).isEqualTo(RUN_ID)
        assertThat(result.runHistory.last().retry).isNull()
        assertThat(result.outcomeProof).isEqualTo(storedContract().contract.outcomeProof)
        assertThat(transactions.calls).isEqualTo(1)
    }

    @Test
    fun `rejects a predecessor without its durable retry link`() {
        prepareTwoAttemptContext(includeRetry = false)

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up predecessor retry event is missing")

        verifyNoInteractions(contracts)
    }

    @Test
    fun `rejects a predecessor returned under the wrong run identity`() {
        prepareTwoAttemptContext()
        `when`(runs.find(TENANT_ID, PREDECESSOR_RUN_ID))
            .thenReturn(storedRun(ResolutionRunId(UUID.randomUUID())))

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up predecessor run identity is inconsistent")

        verifyNoInteractions(contracts)
    }

    private fun prepareTwoAttemptContext(includeRetry: Boolean = true) {
        val initialRun = storedRun(PREDECESSOR_RUN_ID)
        val escalatedRun =
            StoredResolutionRunStart(
                ResolutionRunStart.retry(RUN_ID, initialRun.run, plan()),
                NOW.minusSeconds(60),
            )
        val predecessorReceipt = storedReceipt(PREDECESSOR_RUN_ID, PREDECESSOR_CONSUMPTION_ID, NOW.minusSeconds(70))
        val escalatedReceipt = storedReceipt()
        val predecessorResult = storedResult(PREDECESSOR_RUN_ID, PREDECESSOR_CONSUMPTION_ID, NOW.minusSeconds(70))
        val escalatedResult = storedResult()
        val retry = storedRetry()
        val predecessorState =
            ResolutionRunStateSnapshot(PREDECESSOR_RUN_ID, ResolutionRunState.SUPERSEDED, 2, retry.event.occurredAt)
        val escalatedState = ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ESCALATED, 2, ESCALATED_AT)
        val escalation = storedEscalation(maximumAttempts = 2, sourceAttemptNumber = 2)
        `when`(claims.findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(ownedWork())
        `when`(cases.find(TENANT_ID, CASE_ID)).thenReturn(timeline())
        `when`(runs.find(TENANT_ID, RUN_ID)).thenReturn(escalatedRun)
        `when`(runs.find(TENANT_ID, PREDECESSOR_RUN_ID)).thenReturn(initialRun)
        `when`(transitions.findState(TENANT_ID, RUN_ID)).thenReturn(escalatedState)
        `when`(transitions.findState(TENANT_ID, PREDECESSOR_RUN_ID)).thenReturn(predecessorState)
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(escalatedReceipt)
        `when`(receipts.findByRun(TENANT_ID, PREDECESSOR_RUN_ID)).thenReturn(predecessorReceipt)
        `when`(escalations.find(TENANT_ID, RUN_ID)).thenReturn(escalation)
        `when`(transitions.findByReceipt(TENANT_ID, escalatedReceipt.receipt.authorizationConsumptionId))
            .thenReturn(escalatedResult)
        `when`(transitions.findByReceipt(TENANT_ID, predecessorReceipt.receipt.authorizationConsumptionId))
            .thenReturn(predecessorResult)
        if (includeRetry) {
            `when`(retries.find(TENANT_ID, PREDECESSOR_RUN_ID)).thenReturn(retry)
        }
        `when`(contracts.find(TENANT_ID, escalatedRun.run.contract.key, escalatedRun.run.contract.revision))
            .thenReturn(storedContract())
    }

    @Test
    fun `rejects an escalated attempt without its immutable capability result`() {
        prepareCompleteSingleAttempt()

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up capability result event is missing")

        verifyNoInteractions(contracts)
    }

    @Test
    fun `rejects a capability result that contradicts the failed receipt`() {
        prepareCompleteSingleAttempt()
        `when`(transitions.findByReceipt(TENANT_ID, CapabilityAuthorizationConsumptionId(CONSUMPTION_ID)))
            .thenReturn(storedResult(occurredAt = NOW.minusSeconds(51)))

        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("owned follow-up capability result contradicts its failed receipt")

        verifyNoInteractions(contracts)
    }

    @Test
    fun `does not read case or run records when ownership is hidden`() {
        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(ResolverFollowUpCaseSummaryNotFoundException::class.java)

        verifyNoInteractions(cases, runs, transitions, receipts, escalations, retries, contracts)
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

        verifyNoInteractions(receipts, escalations, retries, contracts)
    }

    @Test
    fun `rejects an escalated handoff backed by a successful connector receipt`() {
        prepareConsistentBase()
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(
            storedReceipt(outcome = CapabilityInvocationOutcome.SUCCEEDED),
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

    private fun prepareCompleteSingleAttempt() {
        prepareConsistentBase()
        `when`(receipts.findByRun(TENANT_ID, RUN_ID)).thenReturn(storedReceipt())
        `when`(escalations.find(TENANT_ID, RUN_ID)).thenReturn(storedEscalation())
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
            HumanFollowUpOwnershipRevision(1),
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

    private fun plan(): ResolutionRunPlan =
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

    private fun storedRun(runId: ResolutionRunId = RUN_ID): StoredResolutionRunStart =
        StoredResolutionRunStart(ResolutionRunStart.create(runId, CASE_ID, plan()), NOW.minusSeconds(120))

    private fun storedReceipt(
        runId: ResolutionRunId = RUN_ID,
        consumptionUuid: UUID = CONSUMPTION_ID,
        completedAt: Instant = NOW.minusSeconds(50),
        outcome: CapabilityInvocationOutcome = CapabilityInvocationOutcome.FAILED,
    ): StoredCapabilityInvocationReceipt {
        val consumptionId = CapabilityAuthorizationConsumptionId(consumptionUuid)
        val receipt =
            CapabilityInvocationReceipt.rehydrate(
                CapabilityInvocationReceiptSnapshot(
                    authorizationConsumptionId = consumptionId,
                    authorizationGrantId = CapabilityAuthorizationGrantId(UUID.randomUUID()),
                    runId = runId,
                    caseId = CASE_ID,
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                    stepId = ResolutionStepId.of("unlock-account"),
                    capability = CapabilityName.of("identity.account.unlock"),
                    connector = ConnectorName.of("identity-stub"),
                    idempotencyKey = consumptionId.value,
                    outcome = outcome,
                    providerOperationReference = ProviderOperationReference.of("identity-stub/operations/failed"),
                    consumptionConsumedAt = completedAt.minusSeconds(10),
                    completedAt = completedAt,
                ),
            )
        return StoredCapabilityInvocationReceipt(receipt, completedAt.plusSeconds(1))
    }

    private fun storedResult(
        runId: ResolutionRunId = RUN_ID,
        consumptionUuid: UUID = CONSUMPTION_ID,
        occurredAt: Instant = NOW.minusSeconds(50),
    ): StoredResolutionRunCapabilityResult =
        StoredResolutionRunCapabilityResult(
            ResolutionRunCapabilityResult.rehydrate(
                ResolutionRunCapabilityResultSnapshot(
                    ResolutionRunEventId(UUID.randomUUID()),
                    runId,
                    1,
                    ResolutionRunEventType.CAPABILITY_FAILED,
                    ResolutionRunState.WAITING_FOR_APPROVAL,
                    ResolutionRunState.ACTION_FAILED,
                    CapabilityAuthorizationConsumptionId(consumptionUuid),
                    CapabilityInvocationOutcome.FAILED,
                    occurredAt,
                ),
            ),
            occurredAt.plusSeconds(1),
        )

    private fun storedRetry(): StoredResolutionRunRetry {
        val occurredAt = NOW.minusSeconds(65)
        return StoredResolutionRunRetry(
            ResolutionRunRetryStarted.rehydrate(
                ResolutionRunRetrySnapshot(
                    ResolutionRunEventId(UUID.randomUUID()),
                    PREDECESSOR_RUN_ID,
                    RUN_ID,
                    2,
                    ResolutionRunState.ACTION_FAILED,
                    ResolutionRunState.SUPERSEDED,
                    null,
                    null,
                    occurredAt,
                ),
            ),
            occurredAt.plusSeconds(1),
        )
    }

    private fun storedContract(): StoredResolutionContractRevision {
        val applicability =
            FactCondition(ContractFactType.of("account.access.state"), ContractFactValue.of("locked"))
        val contract =
            ResolutionContract.define(
                ResolutionContractIdentity(
                    ResolutionContractKey.of("restore-workspace-access"),
                    ResolutionContractRevision.of(1),
                ),
                applicability,
                listOf(applicability.fact),
                listOf(
                    ResolutionStep(
                        ResolutionStepId.of("unlock-account"),
                        CapabilityName.of("identity.account.unlock"),
                        StepRisk.HIGH,
                        ApprovalRequirement.RESOLVER,
                    ),
                ),
                FactCondition(ContractFactType.of("account.access.state"), ContractFactValue.of("ACTIVE")),
            )
        return StoredResolutionContractRevision(contract, NOW.minusSeconds(150))
    }

    private fun storedEscalation(
        eventId: ResolutionRunEventId = ESCALATION_EVENT_ID,
        sourceAttemptNumber: Int = 1,
        maximumAttempts: Int = 1,
    ): StoredResolutionRunEscalation {
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
                            sourceAttemptNumber,
                            maximumAttempts,
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
        val PREDECESSOR_RUN_ID = ResolutionRunId(UUID.randomUUID())
        val ESCALATION_EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val REASON = ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED
        val NOW = Instant.parse("2026-09-25T12:00:00Z")
        val ESCALATED_AT = NOW.minusSeconds(30)
        val CONSUMPTION_ID = UUID.randomUUID()
        val PREDECESSOR_CONSUMPTION_ID = UUID.randomUUID()
    }
}
