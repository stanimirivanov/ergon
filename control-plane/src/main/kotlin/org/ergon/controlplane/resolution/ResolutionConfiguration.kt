package org.ergon.controlplane.resolution

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ApprovalDecisionIdentityGenerator
import org.ergon.controlplane.resolution.application.ApprovalDecisionRecords
import org.ergon.controlplane.resolution.application.ApprovalDecisionRepository
import org.ergon.controlplane.resolution.application.ApprovalDecisionService
import org.ergon.controlplane.resolution.application.ApprovalRequestIdentityGenerator
import org.ergon.controlplane.resolution.application.ApprovalRequestRepository
import org.ergon.controlplane.resolution.application.ApprovalRequestService
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.controlplane.resolution.application.ResolutionRunIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunService
import org.ergon.resolution.domain.ApprovalDecisionId
import org.ergon.resolution.domain.ApprovalRequestId
import org.ergon.resolution.domain.CapabilityPolicyRule
import org.ergon.resolution.domain.ResolutionPolicy
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.UUID

private val builtInApprovalRequestLifetime: Duration = Duration.parse("PT15M")

/** Wires deterministic resolution planning to authoritative case and contract ports. */
@Configuration(proxyBeanMethods = false)
class ResolutionConfiguration {
    @Bean
    fun resolutionReadinessService(
        eventStore: CaseEventStore,
        contractRevisions: ResolutionContractRevisionRepository,
    ) = ResolutionReadinessService(eventStore, contractRevisions)

    @Bean
    fun resolutionPolicy(): ResolutionPolicy =
        ResolutionPolicy.define(
            revision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
            rules =
                listOf(
                    CapabilityPolicyRule(
                        capability = CapabilityName.of("identity.account.unlock"),
                        minimumRisk = StepRisk.HIGH,
                        minimumApproval = ApprovalRequirement.REQUESTER,
                    ),
                ),
        )

    @Bean
    fun resolutionPlanningService(
        readinessService: ResolutionReadinessService,
        policy: ResolutionPolicy,
    ) = ResolutionPlanningService(readinessService, policy)

    @Bean
    fun resolutionRunIdentityGenerator() =
        ResolutionRunIdentityGenerator {
            org.ergon.resolution.domain
                .ResolutionRunId(UUID.randomUUID())
        }

    @Bean
    fun resolutionRunService(
        planningService: ResolutionPlanningService,
        repository: ResolutionRunRepository,
        identities: ResolutionRunIdentityGenerator,
        transactionRunner: TransactionRunner,
    ) = ResolutionRunService(planningService, repository, identities, transactionRunner)

    @Bean
    fun approvalRequestIdentityGenerator() = ApprovalRequestIdentityGenerator { ApprovalRequestId(UUID.randomUUID()) }

    @Bean
    fun approvalRequestService(
        runs: ResolutionRunRepository,
        requests: ApprovalRequestRepository,
        identities: ApprovalRequestIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = ApprovalRequestService(
        runs = runs,
        requests = requests,
        identities = identities,
        transactionRunner = transactionRunner,
        clock = clock,
        lifetime = builtInApprovalRequestLifetime,
    )

    @Bean
    fun approvalDecisionIdentityGenerator(): ApprovalDecisionIdentityGenerator =
        ApprovalDecisionIdentityGenerator {
            ApprovalDecisionId(UUID.randomUUID())
        }

    @Bean
    fun approvalDecisionRecords(
        requests: ApprovalRequestRepository,
        runs: ResolutionRunRepository,
        authorities: HumanAuthorityRepository,
        decisions: ApprovalDecisionRepository,
    ) = ApprovalDecisionRecords(requests, runs, authorities, decisions)

    @Bean
    fun approvalDecisionService(
        records: ApprovalDecisionRecords,
        identities: ApprovalDecisionIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = ApprovalDecisionService(records, identities, transactionRunner, clock)
}
