package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
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
}
