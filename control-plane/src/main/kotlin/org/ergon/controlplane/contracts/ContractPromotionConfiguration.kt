package org.ergon.controlplane.contracts

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ContractPromotionRepository
import org.ergon.controlplane.contracts.application.ContractPromotions
import org.ergon.controlplane.contracts.application.PromotionAuthorityRepository
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentCodec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ContractPromotionConfiguration {
    @Bean
    @Suppress("LongParameterList") // One composition root wires the transaction and existing gate boundaries.
    fun contractPromotions(
        transactions: TransactionRunner,
        repository: ContractPromotionRepository,
        authorities: PromotionAuthorityRepository,
        suites: EvaluationSuites,
        documents: GateDocumentCodec,
        gate: EvaluatePromotionEligibility,
    ) = ContractPromotions(transactions, repository, authorities, suites, documents, gate)
}
