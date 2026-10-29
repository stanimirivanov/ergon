package org.ergon.controlplane.knowledge

import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.knowledge.application.SourceRevisionRepository
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class SourceConfiguration {
    @Bean
    fun sourceRevisionService(
        repository: SourceRevisionRepository,
        authorities: HumanAuthorityRepository,
        clock: Clock,
    ) = SourceRevisionService(repository, authorities, clock)
}
