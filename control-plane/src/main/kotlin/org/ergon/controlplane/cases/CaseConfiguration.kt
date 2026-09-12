package org.ergon.controlplane.cases

import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.CaseProjectionWriter
import org.ergon.controlplane.cases.application.CaseQueryService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.IdentityGenerator
import org.ergon.controlplane.cases.application.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
class CaseConfiguration {
    @Bean
    fun caseCommandService(
        eventStore: CaseEventStore,
        projectionWriter: CaseProjectionWriter,
        identityGenerator: IdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = CaseCommandService(eventStore, projectionWriter, identityGenerator, transactionRunner, clock)

    @Bean
    fun caseQueryService(repository: CaseTimelineRepository) = CaseQueryService(repository)

    @Bean
    fun identityGenerator(): IdentityGenerator = IdentityGenerator(UUID::randomUUID)

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
