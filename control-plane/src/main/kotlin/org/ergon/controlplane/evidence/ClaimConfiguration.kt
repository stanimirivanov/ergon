package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.ClaimProposalRepository
import org.ergon.controlplane.evidence.application.ClaimProposalService
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ClaimConfiguration {
    @Bean
    fun claimProposalService(
        sources: SourceRevisionService,
        @Qualifier("sourceCompilationService") compilation: SourceCompilationService,
        repository: ClaimProposalRepository,
    ) = ClaimProposalService(sources, compilation, repository)
}
