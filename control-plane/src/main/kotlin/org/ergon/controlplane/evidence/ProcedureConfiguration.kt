package org.ergon.controlplane.evidence

import org.ergon.controlplane.evidence.application.ProcedureOutlineParser
import org.ergon.controlplane.evidence.application.ProcedureProposalRepository
import org.ergon.controlplane.evidence.application.ProcedureProposalService
import org.ergon.controlplane.knowledge.application.SourceCompilationService
import org.ergon.controlplane.knowledge.application.SourceRevisionService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ProcedureConfiguration {
    @Bean
    fun procedureProposalService(
        sources: SourceRevisionService,
        @Qualifier("sourceCompilationService") markdown: SourceCompilationService,
        @Qualifier("openApiCompilationService") openApi: SourceCompilationService,
        outline: ProcedureOutlineParser,
        repository: ProcedureProposalRepository,
    ) = ProcedureProposalService(sources, markdown, openApi, outline, repository)
}
