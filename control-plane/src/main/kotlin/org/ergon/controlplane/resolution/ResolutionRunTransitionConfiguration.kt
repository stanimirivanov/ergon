package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAssessmentService
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.resolution.domain.ResolutionRunEventId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/** Wires receipt-backed resolution-run state transitions. */
@Configuration(proxyBeanMethods = false)
class ResolutionRunTransitionConfiguration {
    @Bean
    fun resolutionOutcomeProofAssessmentService(
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        receipts: CapabilityInvocationReceiptRepository,
        contracts: ResolutionContractRevisionRepository,
        eventStore: CaseEventStore,
    ) = ResolutionOutcomeProofAssessmentService(runs, transitions, receipts, contracts, eventStore)

    @Bean
    fun resolutionRunEventIdentityGenerator() =
        ResolutionRunEventIdentityGenerator {
            ResolutionRunEventId(UUID.randomUUID())
        }

    @Bean
    fun resolutionRunCapabilityResultService(
        runs: ResolutionRunRepository,
        receipts: CapabilityInvocationReceiptRepository,
        transitions: ResolutionRunTransitionRepository,
        identities: ResolutionRunEventIdentityGenerator,
        transactionRunner: TransactionRunner,
    ) = ResolutionRunCapabilityResultService(runs, receipts, transitions, identities, transactionRunner)
}
