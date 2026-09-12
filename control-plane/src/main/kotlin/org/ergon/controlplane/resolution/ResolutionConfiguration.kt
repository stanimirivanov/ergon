package org.ergon.controlplane.resolution

import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.StepRisk
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.resolution.domain.CapabilityPolicyRule
import org.ergon.resolution.domain.ResolutionPolicy
import org.ergon.resolution.domain.ResolutionPolicyRevision
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
}
