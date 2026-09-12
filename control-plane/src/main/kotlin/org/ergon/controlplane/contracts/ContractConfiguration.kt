package org.ergon.controlplane.contracts

import org.ergon.controlplane.contracts.application.ResolutionContractDocumentDecoder
import org.ergon.controlplane.contracts.application.ResolutionContractValidationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free contract use cases to their document adapters. */
@Configuration(proxyBeanMethods = false)
class ContractConfiguration {
    @Bean
    fun resolutionContractValidator(decoder: ResolutionContractDocumentDecoder): ResolutionContractValidationService =
        ResolutionContractValidationService(decoder)
}
