package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.controlplane.resolution.application.RunCommandControlGate
import org.ergon.controlplane.resolution.application.RunControlIdentityGenerator
import org.ergon.controlplane.resolution.application.RunControlRepository
import org.ergon.controlplane.resolution.application.RunControlService
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** One default-off policy is injected into every browser command family; runtime writers are intentionally separate. */
@Configuration(proxyBeanMethods = false)
class RunControlConfiguration {
    @Bean
    fun runControlIdentities() = RunControlIdentityGenerator { UUID.randomUUID() }

    @Bean
    fun runCommandControlGate(
        controls: RunControlRepository,
        authorities: HumanAuthorityRepository,
        clock: Clock,
        @Value("\${ergon.run-supervision.control.enabled:false}") enabled: Boolean,
    ) = RunCommandControlGate(controls, authorities, clock, enabled)

    @Bean
    @Suppress("LongParameterList") // Explicit capability-owned serialization, time, identity and persistence ports.
    fun runControlService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        states: ResolutionRunTransitionRepository,
        controls: RunControlRepository,
        transactions: TransactionRunner,
        clock: Clock,
        identities: RunControlIdentityGenerator,
        @Value("\${ergon.run-supervision.control.enabled:false}") enabled: Boolean,
    ) = RunControlService(assignments, authorities, states, controls, transactions, clock, identities, enabled)
}
