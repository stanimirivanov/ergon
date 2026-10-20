package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRecords
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryService
import org.ergon.controlplane.resolution.application.RunCommandControlGate
import org.ergon.controlplane.resolution.application.RunRetryCommandRepository
import org.ergon.controlplane.resolution.application.RunRetryReviewService
import org.ergon.controlplane.resolution.application.RunRetryService
import org.ergon.controlplane.resolution.application.RunRetrySuccessorService
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentIdentityGenerator
import org.ergon.controlplane.resolution.application.RunSupervisorAssignmentRepository
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Reuses the internal retry engine; only the browser admission and successor-supervision policy are new. */
@Configuration(proxyBeanMethods = false)
class RunRetryConfiguration {
    @Bean
    fun runRetrySuccessorService(
        assignments: RunSupervisorAssignmentRepository,
        authorities: HumanAuthorityRepository,
        retries: ResolutionRunRetryRepository,
        transactions: TransactionRunner,
        clock: Clock,
    ) = RunRetrySuccessorService(assignments, authorities, retries, transactions, clock)

    @Bean
    @Suppress("LongParameterList") // Review uses the existing capability-owned source ports and policy.
    fun runRetryReviewService(
        assignments: RunSupervisorAssignmentRepository,
        records: ResolutionRunRetryRecords,
        policy: ResolutionRetryPolicy,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.retry.enabled:false}") enabled: Boolean,
    ) = RunRetryReviewService(assignments, records, policy, transactions, clock, enabled)

    @Bean
    // Explicit transaction, runtime and provenance owners, not infrastructure in the use case.
    @Suppress("LongParameterList")
    fun runRetryService(
        assignments: RunSupervisorAssignmentRepository,
        commands: RunRetryCommandRepository,
        records: ResolutionRunRetryRecords,
        reviews: RunRetryReviewService,
        retries: ResolutionRunRetryService,
        identities: RunSupervisorAssignmentIdentityGenerator,
        transactions: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.run-supervision.retry.enabled:false}") enabled: Boolean,
        controls: RunCommandControlGate,
    ) = RunRetryService(
        assignments,
        commands,
        records,
        reviews,
        retries,
        identities,
        transactions,
        clock,
        enabled,
        controls,
    )
}
