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
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
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
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
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
    private val transactions = RecordingTransactionRunner()
    private val service =
        ResolverFollowUpCaseSummaryService(
            claims,
            cases,
            runs,
            transitions,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    @Test
    fun `returns case and run context after proving current ownership`() {
        val ownedWork = ownedWork()
        val timeline = timeline()
        val run = storedRun()
        val state = ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ESCALATED, 2, NOW)
        `when`(claims.findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(ownedWork)
        `when`(cases.find(TENANT_ID, CASE_ID)).thenReturn(timeline)
        `when`(runs.find(TENANT_ID, RUN_ID)).thenReturn(run)
        `when`(transitions.findState(TENANT_ID, RUN_ID)).thenReturn(state)

        val result = service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result).isEqualTo(ResolverFollowUpCaseSummary(ownedWork, timeline, run, state))
        assertThat(transactions.calls).isEqualTo(1)
        inOrder(claims, cases, runs, transitions).apply {
            verify(claims).findOwnedWorkForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)
            verify(cases).find(TENANT_ID, CASE_ID)
            verify(runs).find(TENANT_ID, RUN_ID)
            verify(transitions).findState(TENANT_ID, RUN_ID)
        }
    }

    @Test
    fun `does not read case or run records when ownership is hidden`() {
        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(ResolverFollowUpCaseSummaryNotFoundException::class.java)

        verifyNoInteractions(cases, runs, transitions)
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
                NOW.minusSeconds(30),
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
    }
}
