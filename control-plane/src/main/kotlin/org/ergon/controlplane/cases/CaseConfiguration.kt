package org.ergon.controlplane.cases

import org.ergon.controlplane.cases.application.CaseAccountAccessFactRepository
import org.ergon.controlplane.cases.application.CaseCommandService
import org.ergon.controlplane.cases.application.CaseEventCommitter
import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.CaseProjectionWriter
import org.ergon.controlplane.cases.application.CaseQueryService
import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.IdentityGenerator
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Explicitly wires the framework-free case application services to their adapters. */
@Configuration(proxyBeanMethods = false)
class CaseConfiguration {
    @Bean
    fun caseCommandService(
        eventStore: CaseEventStore,
        contractRevisions: ResolutionContractRevisionRepository,
        identityGenerator: IdentityGenerator,
        eventCommitter: CaseEventCommitter,
        clock: Clock,
    ) = CaseCommandService(
        eventStore,
        contractRevisions,
        identityGenerator,
        eventCommitter,
        clock,
    )

    @Bean
    fun caseEventCommitter(
        eventStore: CaseEventStore,
        projectionWriter: CaseProjectionWriter,
        identityGenerator: IdentityGenerator,
        transactionRunner: TransactionRunner,
    ) = CaseEventCommitter(eventStore, projectionWriter, identityGenerator, transactionRunner)

    @Bean
    fun caseQueryService(
        repository: CaseTimelineRepository,
        accountAccessFacts: CaseAccountAccessFactRepository,
    ) = CaseQueryService(repository, accountAccessFacts)

    @Bean
    fun identityGenerator(): IdentityGenerator = IdentityGenerator(UUID::randomUUID)

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
