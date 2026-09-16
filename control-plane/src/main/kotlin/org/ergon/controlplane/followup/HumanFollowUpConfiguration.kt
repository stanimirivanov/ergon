package org.ergon.controlplane.followup

import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires durable human follow-up identity and resolver-scoped queries. */
@Configuration(proxyBeanMethods = false)
class HumanFollowUpConfiguration {
    @Bean
    fun humanFollowUpWorkItemIdentityGenerator() =
        HumanFollowUpWorkItemIdentityGenerator {
            HumanFollowUpWorkItemId(UUID.randomUUID())
        }

    @Bean
    fun humanFollowUpWorkItemQueryService(
        repository: HumanFollowUpWorkItemRepository,
        clock: Clock,
    ) = HumanFollowUpWorkItemQueryService(repository, clock)
}
