package org.ergon.controlplane.evaluation

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ContractPromotions
import org.ergon.controlplane.evaluation.application.EvaluatePromotionEligibility
import org.ergon.controlplane.evaluation.application.EvaluationReportCodec
import org.ergon.controlplane.evaluation.application.EvaluationReportRepository
import org.ergon.controlplane.evaluation.application.EvaluationSuites
import org.ergon.controlplane.evaluation.application.GateDocumentCodec
import org.ergon.controlplane.evaluation.application.SimulationLabAuthority
import org.ergon.controlplane.evaluation.application.SimulationLabPromotions
import org.ergon.controlplane.evaluation.application.SimulationLabReports
import org.ergon.controlplane.evaluation.application.SimulationReportDiscovery
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SimulationLabConfiguration {
    @Bean
    fun simulationLabReports(
        authority: SimulationLabAuthority,
        discovery: SimulationReportDiscovery,
        reports: EvaluationReportRepository,
        codec: EvaluationReportCodec,
    ) = SimulationLabReports(authority, discovery, reports, codec)

    @Bean
    @Suppress("LongParameterList") // Explicit composition of existing governance and evaluation capabilities.
    fun simulationLabPromotions(
        authority: SimulationLabAuthority,
        transactions: TransactionRunner,
        promotions: ContractPromotions,
        suites: EvaluationSuites,
        documents: GateDocumentCodec,
        eligibility: EvaluatePromotionEligibility,
    ) = SimulationLabPromotions(authority, transactions, promotions, suites, documents, eligibility)
}
