package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRecords
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationService
import org.ergon.controlplane.resolution.application.RunEscalationCommandRepository
import org.ergon.controlplane.resolution.application.RunEscalationReviewService
import org.ergon.controlplane.resolution.application.RunEscalationService
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Binds browser admission to the existing deterministic runtime escalation and follow-up owners. */
@Configuration(proxyBeanMethods = false)
class RunEscalationConfiguration {
    @Bean
    @Suppress("LongParameterList") // Explicit capability-owned sources, transaction and rollout policy.
    fun runEscalationReviewService(
        assignments: RunSupervisorAssignmentRepository,
        records: ResolutionRunEscalationRecords,
        policy: ResolutionRetryPolicy,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.escalation.enabled:false}") enabled: Boolean,
    ) = RunEscalationReviewService(assignments, records, policy, transactions, clock, enabled)

    @Bean
    @Suppress("LongParameterList") // Do not hide runtime or durability owners behind a service locator.
    fun runEscalationService(
        assignments: RunSupervisorAssignmentRepository,
        commands: RunEscalationCommandRepository,
        records: ResolutionRunEscalationRecords,
        policy: ResolutionRetryPolicy,
        escalations: ResolutionRunEscalationService,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.escalation.enabled:false}") enabled: Boolean,
    ) = RunEscalationService(assignments, commands, records, policy, escalations, transactions, clock, enabled)
}
