package org.ergon.controlplane.followup

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires durable human follow-up identities, resolver queries, and claiming. */
@Configuration(proxyBeanMethods = false)
class HumanFollowUpConfiguration {
    @Bean
    fun humanFollowUpWorkItemIdentityGenerator() =
        HumanFollowUpWorkItemIdentityGenerator {
            HumanFollowUpWorkItemId(UUID.randomUUID())
        }

    @Bean
    fun humanFollowUpClaimIdentityGenerator() =
        HumanFollowUpClaimIdentityGenerator {
            HumanFollowUpClaimId(UUID.randomUUID())
        }

    @Bean
    fun humanFollowUpWorkItemQueryService(
        repository: HumanFollowUpWorkItemRepository,
        clock: Clock,
    ) = HumanFollowUpWorkItemQueryService(repository, clock)

    @Bean
    fun humanFollowUpClaimService(
        claims: HumanFollowUpClaimRepository,
        authorities: HumanAuthorityRepository,
        identities: HumanFollowUpClaimIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = HumanFollowUpClaimService(claims, authorities, identities, transactionRunner, clock)
}
