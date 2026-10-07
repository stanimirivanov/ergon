package org.ergon.controlplane.followup

import org.ergon.controlplane.cases.application.CaseTimelineRepository
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.contracts.application.ResolutionContractRevisionRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimCommandRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.HumanFollowUpClaimService
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseRepository
import org.ergon.controlplane.followup.application.HumanFollowUpReleaseService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemQueryService
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.controlplane.followup.application.ResolverFollowUpCaseSummaryService
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.CapabilityInvocationReceiptRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunRetryRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires durable human follow-up identities, resolver queries, and claiming. */
@Configuration(proxyBeanMethods = false)
class HumanFollowUpConfiguration {
    @Bean
    fun humanFollowUpWorkItemIdentityGenerator() =
        HumanFollowUpWorkItemIdentityGenerator {
            HumanFollowUpWorkItemId(UUID.randomUUID())
        }

    @Bean
    fun humanFollowUpClaimIdentityGenerator() =
        HumanFollowUpClaimIdentityGenerator {
            HumanFollowUpClaimId(UUID.randomUUID())
        }

    @Bean
    fun humanFollowUpWorkItemQueryService(
        repository: HumanFollowUpWorkItemRepository,
        clock: Clock,
    ) = HumanFollowUpWorkItemQueryService(repository, clock)

    @Bean
    @Suppress("LongParameterList") // Composition root wires claim and command persistence explicitly.
    fun humanFollowUpClaimService(
        claims: HumanFollowUpClaimRepository,
        commands: HumanFollowUpClaimCommandRepository,
        authorities: HumanAuthorityRepository,
        identities: HumanFollowUpClaimIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = HumanFollowUpClaimService(claims, commands, authorities, identities, transactionRunner, clock)

    @Bean
    @Suppress("LongParameterList") // Composition root makes the release transaction dependencies explicit.
    fun humanFollowUpReleaseService(
        claims: HumanFollowUpClaimRepository,
        commands: HumanFollowUpClaimCommandRepository,
        releases: HumanFollowUpReleaseRepository,
        authorities: HumanAuthorityRepository,
        transactionRunner: TransactionRunner,
        clock: Clock,
        @Value("\${ergon.follow-up.release-enabled}") enabled: Boolean,
    ) = HumanFollowUpReleaseService(claims, commands, releases, authorities, transactionRunner, clock, enabled)

    @Bean
    @Suppress("LongParameterList") // Composition roots make dependencies explicit for Spring wiring.
    fun resolverFollowUpCaseSummaryService(
        claims: HumanFollowUpClaimRepository,
        cases: CaseTimelineRepository,
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        receipts: CapabilityInvocationReceiptRepository,
        escalations: ResolutionRunEscalationRepository,
        retries: ResolutionRunRetryRepository,
        contracts: ResolutionContractRevisionRepository,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = ResolverFollowUpCaseSummaryService(
        claims,
        cases,
        runs,
        transitions,
        receipts,
        escalations,
        retries,
        contracts,
        transactionRunner,
        clock,
    )
}
