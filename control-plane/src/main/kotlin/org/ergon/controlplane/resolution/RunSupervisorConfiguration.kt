package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.AssignedRunConsoleService
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentIdentityGenerator
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires first-assignment commands and assigned-only browser reads to their application ports. */
@Configuration(proxyBeanMethods = false)
class RunSupervisorConfiguration {
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
    @Suppress("LongParameterList") // Composition root wires six distinct use-case dependencies explicitly.
    fun assignedRunConsoleService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = AssignedRunConsoleService(assignments, authorities, runs, transitions, transactionRunner, clock)
}
