package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunCancellationRecords
import org.ergon.controlplane.resolution.application.RunCancellationRepository
import org.ergon.controlplane.resolution.application.RunCancellationService
import org.ergon.controlplane.resolution.application.RunCommandControlGate
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Cancellation is independently disabled until every state reader and authorization writer is upgraded. */
@Configuration(proxyBeanMethods = false)
class RunCancellationConfiguration {
    @Bean
    fun runCancellationRecords(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        transitions: ResolutionRunTransitionRepository,
        cancellations: RunCancellationRepository,
    ) = RunCancellationRecords(assignments, authorities, transitions, cancellations)

    @Bean
    @Suppress("LongParameterList") // Explicit transaction, time, event identity and mandatory browser fence owners.
    fun runCancellationService(
        records: RunCancellationRecords,
        identities: ResolutionRunEventIdentityGenerator,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.cancellation.enabled:false}") enabled: Boolean,
        controls: RunCommandControlGate,
    ) = RunCancellationService(records, identities, transactions, clock, enabled, controls)
}
