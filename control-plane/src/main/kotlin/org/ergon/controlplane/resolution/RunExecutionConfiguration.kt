package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ApprovalRequestRepository
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionRepository
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationConsumptionService
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantService
import org.ergon.controlplane.resolution.application.CapabilityConnectorGateway
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationService
import org.ergon.controlplane.resolution.application.CapabilityInvocationTicker
import org.ergon.controlplane.resolution.application.CapabilityRouteRepository
import org.ergon.controlplane.resolution.application.ResolutionRunCapabilityResultService
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunApprovalReviewReader
import org.ergon.controlplane.resolution.application.RunCommandControlGate
import org.ergon.controlplane.resolution.application.RunExecutionAdmissionService
import org.ergon.controlplane.resolution.application.RunExecutionCommandRepository
import org.ergon.controlplane.resolution.application.RunExecutionHistoryReader
import org.ergon.controlplane.resolution.application.RunExecutionHistoryService
import org.ergon.controlplane.resolution.application.RunExecutionPrerequisites
import org.ergon.controlplane.resolution.application.RunExecutionReviewService
import org.ergon.controlplane.resolution.application.RunExecutionService
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.UncertainCapabilityConnectorGateway
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Browser-specific composition; existing internal grant, invocation and projection services retain their contracts. */
@Configuration(proxyBeanMethods = false)
class RunExecutionConfiguration {
    @Bean
    fun runExecutionHistoryService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        history: RunExecutionHistoryReader,
        transactions: TransactionRunner,
        clock: Clock,
    ) = RunExecutionHistoryService(assignments, authorities, history, transactions, clock)

    @Bean
    fun runExecutionPrerequisites(
        reviews: RunApprovalReviewReader,
        requests: ApprovalRequestRepository,
        grants: CapabilityAuthorizationGrantService,
        consumptions: CapabilityAuthorizationConsumptionService,
    ) = RunExecutionPrerequisites(reviews, requests, grants, consumptions)

    @Bean
    @Suppress("LongParameterList") // Distinct application ports own authority, state, reservation and immutable audit.
    fun runExecutionAdmissions(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        runs: ResolutionRunRepository,
        states: ResolutionRunTransitionRepository,
        prerequisites: RunExecutionPrerequisites,
        commands: RunExecutionCommandRepository,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.execution.enabled:false}") enabled: Boolean,
        controls: RunCommandControlGate,
    ) = RunExecutionAdmissionService(
        assignments,
        authorities,
        runs,
        states,
        prerequisites,
        commands,
        transactions,
        clock,
        enabled,
        controls,
    )

    @Bean
    @Suppress("LongParameterList") // Reuse invocation with only the connector error boundary adapted.
    fun runExecutionService(
        admissions: RunExecutionAdmissionService,
        consumptions: CapabilityAuthorizationConsumptionRepository,
        receipts: CapabilityInvocationReceiptRepository,
        gateway: CapabilityConnectorGateway,
        ticker: CapabilityInvocationTicker,
        results: ResolutionRunCapabilityResultService,
        states: ResolutionRunTransitionRepository,
        authorities: HumanAuthorityRepository,
        transactions: TransactionRunner,
        clock: Clock,
    ): RunExecutionService {
        val invocation =
            CapabilityInvocationService(
                consumptions,
                receipts,
                UncertainCapabilityConnectorGateway(gateway),
                transactions,
                clock,
                ticker,
            )
        return RunExecutionService(admissions, invocation, results, receipts, states, authorities, transactions, clock)
    }

    @Bean
    @Suppress("LongParameterList") // Eligibility reads reuse the existing authority-first Console boundary.
    fun runExecutionReviewService(
        console: AssignedRunConsoleService,
        commands: RunExecutionCommandRepository,
        routes: CapabilityRouteRepository,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.execution.enabled:false}") enabled: Boolean,
    ) = RunExecutionReviewService(console, commands, routes, transactions, clock, enabled)
}
