package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.CaseEventStore
import org.ergon.controlplane.cases.application.CaseProjectionWriter
import org.ergon.controlplane.cases.application.IdentityGenerator
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionOutcomeCaseCloser
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAcceptanceService
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAcceptedEventFactory
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAssessmentService
import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofCompletion
import org.ergon.controlplane.resolution.application.ResolutionPlanningService
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRecords
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.ergon.resolution.domain.ResolutionRetryPolicyRevision
import org.ergon.resolution.domain.ResolutionRunEventId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires receipt-backed resolution-run state transitions. */
@Configuration(proxyBeanMethods = false)
class ResolutionRunTransitionConfiguration {
    @Bean
    fun resolutionRunRetryRecords(
        planning: ResolutionPlanningService,
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        retries: ResolutionRunRetryRepository,
        authorities: HumanAuthorityRepository,
    ) = ResolutionRunRetryRecords(planning, runs, transitions, retries, authorities)

    @Bean
    @Suppress("LongParameterList") // Composition roots make dependencies explicit for Spring wiring.
    fun resolutionRunRetryService(
        records: ResolutionRunRetryRecords,
        runIdentities: ResolutionRunIdentityGenerator,
        eventIdentities: ResolutionRunEventIdentityGenerator,
        retryPolicy: ResolutionRetryPolicy,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = ResolutionRunRetryService(records, runIdentities, eventIdentities, retryPolicy, transactionRunner, clock)

    /**
     * Limits the initial recovery slice to one explicit retry.
     *
     * The revision is persisted with every new decision so changing this rule
     * requires an intentional code, documentation, and ADR update.
     */
    @Bean
    fun resolutionRetryPolicy() =
        ResolutionRetryPolicy.define(
            ResolutionRetryPolicyRevision.of("ergon.dev/policy/resolution-retry/v1"),
            maximumAttempts = 2,
        )

    @Bean
    fun resolutionOutcomeCaseCloser(
        eventStore: CaseEventStore,
        projectionWriter: CaseProjectionWriter,
        identities: IdentityGenerator,
    ) = ResolutionOutcomeCaseCloser(eventStore, projectionWriter, identities)

    @Bean
    fun resolutionOutcomeProofAcceptedEventFactory(
        identities: ResolutionRunEventIdentityGenerator,
        clock: Clock,
    ) = ResolutionOutcomeProofAcceptedEventFactory(identities, clock)

    @Bean
    fun resolutionOutcomeProofCompletion(
        eventFactory: ResolutionOutcomeProofAcceptedEventFactory,
        caseCloser: ResolutionOutcomeCaseCloser,
    ) = ResolutionOutcomeProofCompletion(eventFactory, caseCloser)

    @Bean
    fun resolutionOutcomeProofAcceptanceService(
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        assessments: ResolutionOutcomeProofAssessmentService,
        completion: ResolutionOutcomeProofCompletion,
        transactionRunner: TransactionRunner,
    ) = ResolutionOutcomeProofAcceptanceService(
        runs,
        transitions,
        assessments,
        completion,
        transactionRunner,
    )

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
