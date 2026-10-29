package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.ContradictionRepository
import org.ergon.controlplane.evidence.application.ContradictionService
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ContradictionConfiguration {
    @Bean
    fun contradictionService(
        authorities: HumanAuthorityRepository,
        sources: SourceRevisionService,
        repository: ContradictionRepository,
        clock: Clock,
    ) = ContradictionService(authorities, sources, repository, clock)
}
