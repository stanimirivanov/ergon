package org.ergon.controlplane.identity

import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityIdentityGenerator
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.HumanAuthorityService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires human identity and authority-evidence use cases to tenant-scoped persistence. */
@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {
    @Bean
    fun humanAuthorityIdentityGenerator() = HumanAuthorityIdentityGenerator(UUID::randomUUID)

    @Bean
    fun humanAuthorityService(
        repository: HumanAuthorityRepository,
        cases: CaseTimelineRepository,
        identities: HumanAuthorityIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = HumanAuthorityService(repository, cases, identities, transactionRunner, clock)
}
