package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.controlplane.followup.application.StoredHumanFollowUpWorkItem
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
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
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationBasis
import org.ergon.resolution.domain.ResolutionRunEscalationRequested
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ResolutionRunEscalationServiceTest {
    private val runs = mock(ResolutionRunRepository::class.java)
    private val transitions = mock(ResolutionRunTransitionRepository::class.java)
    private val escalations = mock(ResolutionRunEscalationRepository::class.java)
    private val followUps = mock(HumanFollowUpWorkItemRepository::class.java)
    private val authorities = mock(HumanAuthorityRepository::class.java)
    private val root = ResolutionRunStart.create(ResolutionRunId(UUID.randomUUID()), CaseId(UUID.randomUUID()), plan())
    private val finalAttempt = ResolutionRunStart.retry(ResolutionRunId(UUID.randomUUID()), root, plan())

    @Test
    fun `remaining retry budget prevents escalation before identity allocation`() {
        prepare(root, ResolutionRunState.ACTION_FAILED, 1)

        val thrown = catchThrowable { service().escalate(TENANT.value, root.id.value, ACTOR.value) }

        assertThat(thrown).isInstanceOf(ResolutionRunRetryBudgetAvailableException::class.java)
        verify(escalations).find(TENANT, root.id)
        verifyNoMoreInteractions(escalations)
    }

    @Test
    fun `exhausted attempt appends an attributed escalation`() {
        prepare(finalAttempt, ResolutionRunState.ACTION_FAILED, 1)
        val event =
            ResolutionRunEscalationRequested.request(
                EVENT_ID,
                ResolutionRunEscalationBasis(
                    finalAttempt,
                    ResolutionRunStateSnapshot(finalAttempt.id, ResolutionRunState.ACTION_FAILED, 1, NOW),
                    evidence(),
                    ResolutionRetryPolicy.define(RETRY_REVISION, 2).evaluate(2)
                        as org.ergon.resolution.domain.ResolutionRetryEligibility.Denied,
                ),
                NOW,
            )
        `when`(escalations.append(TENANT, event)).thenReturn(
            ResolutionRunEscalationRecording(
                StoredResolutionRunEscalation(event, NOW),
                ResolutionRunStateSnapshot(event.runId, event.toState, event.sequence, NOW),
                created = true,
            ),
        )
        val workItem =
            HumanFollowUpWorkItem.open(
                WORK_ITEM_ID,
                HumanFollowUpSource(finalAttempt.caseId, finalAttempt.id, event.id, event.reason),
                HumanFollowUpQueueKey.ACCESS_RESTORATION,
                NOW,
            )
        `when`(followUps.create(TENANT, workItem)).thenReturn(StoredHumanFollowUpWorkItem(workItem, NOW))

        val result = service().escalate(TENANT.value, finalAttempt.id.value, ACTOR.value)

        assertThat(result.escalation.currentState.state).isEqualTo(ResolutionRunState.ESCALATED)
        assertThat(result.escalation.storedEvent.event.authorization.actorId).isEqualTo(ACTOR)
        assertThat(result.escalation.storedEvent.event.retryDenial.sourceAttemptNumber).isEqualTo(2)
        assertThat(result.followUp.item).isEqualTo(workItem)
        verify(escalations).append(TENANT, result.escalation.storedEvent.event)
        verify(followUps).create(TENANT, workItem)
    }

    private fun prepare(
        run: ResolutionRunStart,
        state: ResolutionRunState,
        version: Long,
    ) {
        `when`(authorities.findCurrent(TENANT, ACTOR, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(StoredApprovalAuthorityEvidence(evidence(), NOW))
        `when`(runs.find(TENANT, run.id)).thenReturn(StoredResolutionRunStart(run, NOW))
        `when`(transitions.lockState(TENANT, run.id))
            .thenReturn(ResolutionRunStateSnapshot(run.id, state, version, NOW))
    }

    private fun service() =
        ResolutionRunEscalationService(
            ResolutionRunEscalationRecords(runs, transitions, escalations, followUps, authorities),
            ResolutionRetryPolicy.define(RETRY_REVISION, 2),
            ResolutionRunEscalationIdentityGenerators(
                ResolutionRunEventIdentityGenerator { EVENT_ID },
                HumanFollowUpWorkItemIdentityGenerator { WORK_ITEM_ID },
            ),
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            },
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun evidence() =
        ApprovalAuthorityEvidence(
            EVIDENCE_ID,
            ACTOR,
            ApprovalAuthority.RESOLVER,
            null,
            ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
            NOW.minusSeconds(60),
            NOW.plusSeconds(60),
        )

    private fun plan() =
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

    private companion object {
        val TENANT = TenantId(UUID.randomUUID())
        val ACTOR = HumanActorId(UUID.randomUUID())
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val RETRY_REVISION = ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v1")
        val EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val NOW = Instant.parse("2026-09-15T10:00:00Z")
    }
}
