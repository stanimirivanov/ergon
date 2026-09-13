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
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ApprovalRequestTest {
    @Test
    fun `request preserves required human authority and bounded validity`() {
        val request = ApprovalRequest.create(approvalId(), run(ApprovalRequirement.RESOLVER), NOW, LIFETIME)

        assertThat(request.authority).isEqualTo(ApprovalAuthority.RESOLVER)
        assertThat(request.requestedAt).isEqualTo(NOW)
        assertThat(request.expiresAt).isEqualTo(NOW.plus(LIFETIME))
    }

    @Test
    fun `request expires at the exact end of its half open interval`() {
        val request = ApprovalRequest.create(approvalId(), run(ApprovalRequirement.REQUESTER), NOW, LIFETIME)

        assertThat(request.statusAt(request.expiresAt.minusNanos(1))).isEqualTo(ApprovalRequestStatus.PENDING)
        assertThat(request.statusAt(request.expiresAt)).isEqualTo(ApprovalRequestStatus.EXPIRED)
    }

    @Test
    fun `request rejects runs that do not require human approval`() {
        assertThatThrownBy {
            ApprovalRequest.create(approvalId(), run(ApprovalRequirement.NONE), NOW, LIFETIME)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("resolution run is not waiting for approval")
    }

    @Test
    fun `request rejects unbounded approval windows`() {
        assertThatThrownBy {
            ApprovalRequest.create(
                approvalId(),
                run(ApprovalRequirement.REQUESTER),
                NOW,
                ApprovalRequest.MAX_LIFETIME.plusSeconds(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be positive and not exceed")
    }

    private fun run(approval: ApprovalRequirement): ResolutionRunStart =
        ResolutionRunStart.create(
            id = ResolutionRunId(UUID.randomUUID()),
            caseId = CaseId(UUID.randomUUID()),
            plan =
                ResolutionRunPlan(
                    caseStreamVersion = 4,
                    contract =
                        ResolutionContractIdentity(
                            ResolutionContractKey.of("restore-workspace-access"),
                            ResolutionContractRevision.of(1),
                        ),
                    policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                    stepId = ResolutionStepId.of("unlock-account"),
                    capability = CapabilityName.of("identity.account.unlock"),
                    decision = StepPolicyDecision.Requirements(StepRisk.MEDIUM, approval),
                ),
        )

    private fun approvalId() = ApprovalRequestId(UUID.randomUUID())

    companion object {
        private val NOW = Instant.parse("2026-09-13T10:00:00Z")
        private val LIFETIME: Duration = Duration.ofMinutes(15)
    }
}
