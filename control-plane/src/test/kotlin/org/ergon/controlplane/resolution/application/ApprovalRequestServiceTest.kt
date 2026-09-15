package org.ergon.controlplane.resolution.application

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalRequest
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.ergon.resolution.domain.ResolutionRunId
import org.ergon.resolution.domain.ResolutionRunPlan
import org.ergon.resolution.domain.ResolutionRunStart
import org.ergon.resolution.domain.StepPolicyDecision
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApprovalRequestServiceTest {
    @Test
    fun `run without a human prerequisite cannot allocate or persist an approval request`() {
        val tenantId = UUID.randomUUID()
        val run = runWithoutHumanApproval()
        val service =
            ApprovalRequestService(
                runs = SingleRunRepository(run),
                requests = NeverCalledApprovalRequestRepository,
                identities = ApprovalRequestIdentityGenerator { error("identity must not be allocated") },
                transactionRunner = ApprovalImmediateTransactionRunner,
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
                lifetime = Duration.ofMinutes(15),
            )

        assertThatThrownBy { service.request(tenantId, run.id.value) }
            .isInstanceOf(ApprovalNotRequiredException::class.java)
            .hasMessageContaining("does not require human approval")
    }

    private fun runWithoutHumanApproval(): ResolutionRunStart =
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
                    stepId = ResolutionStepId.of("observe-account"),
                    capability = CapabilityName.of("identity.account.observe"),
                    decision = StepPolicyDecision.Requirements(StepRisk.LOW, ApprovalRequirement.NONE),
                ),
        )

    companion object {
        private val NOW = Instant.parse("2026-09-13T10:00:00Z")
    }
}

private class SingleRunRepository(
    private val run: ResolutionRunStart,
) : ResolutionRunRepository {
    override fun createRetry(
        tenantId: TenantId,
        run: ResolutionRunStart,
    ): StoredResolutionRunStart = error("retry creation is not used")

    override fun create(
        tenantId: TenantId,
        run: ResolutionRunStart,
    ): StoredResolutionRunStart = error("run creation is not used")

    override fun find(
        tenantId: TenantId,
        runId: ResolutionRunId,
    ): StoredResolutionRunStart? = StoredResolutionRunStart(run, Instant.EPOCH)
}

private object NeverCalledApprovalRequestRepository : ApprovalRequestRepository {
    override fun create(
        tenantId: TenantId,
        request: ApprovalRequest,
    ): StoredApprovalRequest = error("approval request must not be persisted")

    override fun find(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): StoredApprovalRequest? = error("approval request lookup is not used")

    override fun lockForDecision(
        tenantId: TenantId,
        requestId: ApprovalRequestId,
    ): StoredApprovalRequest? = error("approval request locking is not used")
}

private object ApprovalImmediateTransactionRunner : TransactionRunner {
    override fun <T : Any> required(block: () -> T): T = block()
}
