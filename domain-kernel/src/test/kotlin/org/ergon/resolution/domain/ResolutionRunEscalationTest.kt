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

class ResolutionRunEscalationTest {
    @Test
    fun `exhausted failed attempt records attributable terminal follow-up`() {
        val event = ResolutionRunEscalationRequested.request(EVENT_ID, basis(), NOW)

        assertThat(event.type).isEqualTo(ResolutionRunEventType.ESCALATION_REQUESTED)
        assertThat(event.sequence).isEqualTo(2)
        assertThat(event.fromState).isEqualTo(ResolutionRunState.ACTION_FAILED)
        assertThat(event.toState).isEqualTo(ResolutionRunState.ESCALATED)
        assertThat(event.reason).isEqualTo(ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED)
        assertThat(event.authorization)
            .isEqualTo(ResolutionRunEscalationAuthorization(ACTOR_ID, EVIDENCE_ID))
        assertThat(event.retryDenial).isEqualTo(denial())
    }

    @Test
    fun `escalation rejects a denial for another attempt`() {
        val wrongDenial = ResolutionRetryEligibility.Denied(RETRY_REVISION, 3, 2, DENIAL_REASON)

        assertThatThrownBy {
            ResolutionRunEscalationRequested.request(EVENT_ID, basis().copy(retryDenial = wrongDenial), NOW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("retry denial belongs to another source attempt")
    }

    @Test
    fun `escalation rejects non resolver authority`() {
        val requester = evidence().copy(authority = ApprovalAuthority.REQUESTER, caseId = CaseId(UUID.randomUUID()))

        assertThatThrownBy {
            ResolutionRunEscalationRequested.request(EVENT_ID, basis().copy(authorityEvidence = requester), NOW)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("resolution run escalation requires tenant-wide resolver authority")
    }

    @Test
    fun `rehydration validates the durable event type`() {
        val event = ResolutionRunEscalationRequested.request(EVENT_ID, basis(), NOW)
        val snapshot =
            ResolutionRunEscalationSnapshot(
                event.id,
                event.runId,
                event.sequence,
                ResolutionRunEventType.RETRY_STARTED,
                event.fromState,
                event.toState,
                event.reason,
                event.authorization,
                event.retryDenial,
                event.occurredAt,
            )

        assertThatThrownBy { ResolutionRunEscalationRequested.rehydrate(snapshot) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("escalation event type must be escalation requested")
    }

    private fun basis() = ResolutionRunEscalationBasis(run(), state(), evidence(), denial())

    private fun run(): ResolutionRunStart {
        val root = ResolutionRunStart.create(ResolutionRunId(UUID.randomUUID()), CaseId(UUID.randomUUID()), plan())
        return ResolutionRunStart.retry(RUN_ID, root, plan())
    }

    private fun state() = ResolutionRunStateSnapshot(RUN_ID, ResolutionRunState.ACTION_FAILED, 1, NOW.minusSeconds(1))

    private fun denial() = ResolutionRetryEligibility.Denied(RETRY_REVISION, 2, 2, DENIAL_REASON)

    private fun evidence() =
        ApprovalAuthorityEvidence(
            EVIDENCE_ID,
            ACTOR_ID,
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
        val RUN_ID = ResolutionRunId(UUID.randomUUID())
        val EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val RETRY_REVISION = ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v1")
        val DENIAL_REASON = ResolutionRetryDenialReason.ATTEMPT_LIMIT_REACHED
        val NOW = Instant.parse("2026-09-15T10:00:00Z")
    }
}
