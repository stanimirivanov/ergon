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
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRetryEligibility
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunRetryBasis
import org.ergon.resolution.domain.ResolutionRunRetrySnapshot
import org.ergon.resolution.domain.ResolutionRunRetryStarted
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.ResolutionRunState
import org.ergon.resolution.domain.ResolutionRunStateSnapshot
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ResolutionRunRetryServiceTest {
    private val planning = mock(ResolutionPlanner::class.java)
    private val runs = mock(ResolutionRunRepository::class.java)
    private val transitions = mock(ResolutionRunTransitionRepository::class.java)
    private val retries = mock(ResolutionRunRetryRepository::class.java)
    private val authorities = mock(HumanAuthorityRepository::class.java)
    private val root = ResolutionRunStart.create(ResolutionRunId(UUID.randomUUID()), CaseId(UUID.randomUUID()), plan())
    private val successor = ResolutionRunStart.retry(ResolutionRunId(UUID.randomUUID()), root, plan())

    @Test
    fun `attempt limit denial precedes planning identity allocation and persistence`() {
        prepareSource(successor, ResolutionRunState.ACTION_FAILED, 1)
        val thrown = catchThrowable { service(2).retry(TENANT.value, successor.id.value, 4, ACTOR.value) }
        assertThat(thrown).isInstanceOf(ResolutionRunRetryLimitReachedException::class.java)
        val denial = thrown as ResolutionRunRetryLimitReachedException
        assertThat(denial.sourceAttemptNumber).isEqualTo(2)
        assertThat(denial.maximumAttempts).isEqualTo(2)
        assertThat(denial.policyRevision).isEqualTo(RETRY_POLICY_REVISION)
        verifyNoInteractions(planning)
    }

    @Test
    fun `existing retry replays even when current policy permits no retry`() {
        val eligibility = ResolutionRetryEligibility.Eligible(RETRY_POLICY_REVISION, 1, 2)
        val event =
            ResolutionRunRetryStarted.start(
                ResolutionRunEventId(UUID.randomUUID()),
                ResolutionRunRetryBasis(
                    root,
                    state(root, ResolutionRunState.ACTION_FAILED, 1),
                    successor,
                    evidence(),
                    eligibility,
                ),
                NOW,
            )
        assertReplay(event)
    }

    @Test
    fun `legacy replay retains its absent eligibility rather than applying current policy`() {
        val event =
            ResolutionRunRetryStarted.rehydrate(
                ResolutionRunRetrySnapshot(
                    ResolutionRunEventId(UUID.randomUUID()),
                    root.id,
                    successor.id,
                    2,
                    ResolutionRunState.ACTION_FAILED,
                    ResolutionRunState.SUPERSEDED,
                    authorization = null,
                    eligibility = null,
                    NOW,
                ),
            )
        assertReplay(event)
    }

    private fun assertReplay(event: ResolutionRunRetryStarted) {
        prepareSource(root, ResolutionRunState.SUPERSEDED, 2)
        `when`(retries.find(TENANT, root.id)).thenReturn(StoredResolutionRunRetry(event, NOW))
        `when`(runs.find(TENANT, successor.id)).thenReturn(StoredResolutionRunStart(successor, NOW))
        val result = service(1).retry(TENANT.value, root.id.value, 1, ACTOR.value)
        assertThat(result.recording.created).isFalse()
        assertThat(result.recording.storedEvent.event).isEqualTo(event)
        assertThat(result.replacementRun.run).isEqualTo(successor)
        verifyNoInteractions(planning)
    }

    private fun prepareSource(
        run: ResolutionRunStart,
        state: ResolutionRunState,
        version: Long,
    ) {
        `when`(authorities.findCurrent(TENANT, ACTOR, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(StoredApprovalAuthorityEvidence(evidence(), NOW))
        `when`(runs.find(TENANT, run.id)).thenReturn(StoredResolutionRunStart(run, NOW))
        `when`(transitions.lockState(TENANT, run.id)).thenReturn(state(run, state, version))
    }

    private fun service(maximumAttempts: Int) =
        ResolutionRunRetryService(
            ResolutionRunRetryRecords(planning, runs, transitions, retries, authorities),
            ResolutionRunIdentityGenerator { error("denial and replay must not allocate a run identity") },
            ResolutionRunEventIdentityGenerator { error("denial and replay must not allocate an event identity") },
            ResolutionRetryPolicy.define(RETRY_POLICY_REVISION, maximumAttempts),
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            },
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun state(
        run: ResolutionRunStart,
        state: ResolutionRunState,
        version: Long,
    ) = ResolutionRunStateSnapshot(run.id, state, version, NOW)

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
            StepPolicyDecision.Requirements(StepRisk.HIGH, ApprovalRequirement.REQUESTER),
        )

    private companion object {
        val TENANT = TenantId(UUID.randomUUID())
        val ACTOR = HumanActorId(UUID.randomUUID())
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val NOW = Instant.parse("2026-09-15T10:00:00Z")
        val RETRY_POLICY_REVISION = ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v1")
    }
}
