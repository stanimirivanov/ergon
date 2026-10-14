package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.RecordedRunTransitionReader
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentIdentityGenerator
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.ergon.controlplane.resolution.application.RunSupervisorCandidateReader
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverRepository
import org.ergon.controlplane.resolution.application.RunSupervisorHandoverService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires immutable initial assignments, revisioned transfers and current-supervisor browser reads. */
@Configuration(proxyBeanMethods = false)
class RunSupervisorConfiguration {
    @Bean
    @Suppress("LongParameterList") // Application-owned identity, state, and audit boundaries remain distinct.
    fun runSupervisorHandoverService(
        assignments: RunSupervisorAssignmentRepository,
        handovers: RunSupervisorHandoverRepository,
        candidates: RunSupervisorCandidateReader,
        authorities: HumanAuthorityRepository,
        transitions: ResolutionRunTransitionRepository,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.handover.enabled:false}") handoverEnabled: Boolean,
    ) = RunSupervisorHandoverService(
        assignments,
        handovers,
        candidates,
        authorities,
        transitions,
        transactions,
        clock,
        handoverEnabled,
    )

    @Bean
    fun runSupervisorAssignmentIdentityGenerator() = RunSupervisorAssignmentIdentityGenerator { UUID.randomUUID() }

    @Bean
    fun runSupervisorAssignmentService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        identities: RunSupervisorAssignmentIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = RunSupervisorAssignmentService(assignments, authorities, identities, transactionRunner, clock)

    @Bean
    @Suppress("LongParameterList") // Composition root wires the assigned read's distinct ports explicitly.
    fun assignedRunConsoleService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        transactionRunner: TransactionRunner,
        clock: Clock,
        receipts: CapabilityInvocationReceiptRepository,
        historyReader: RecordedRunTransitionReader,
        @Value("\${ergon.run-supervision.handover.enabled:false}") handoverEnabled: Boolean,
    ) = AssignedRunConsoleService(
        assignments,
        authorities,
        runs,
        transitions,
        transactionRunner,
        clock,
        receipts,
        historyReader,
        handoverEnabled,
    )
}
