package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemIdentityGenerator
import org.ergon.controlplane.followup.application.HumanFollowUpWorkItemRepository
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationIdentityGenerators
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRecords
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationRepository
import org.ergon.controlplane.resolution.application.ResolutionRunEscalationService
import org.ergon.controlplane.resolution.application.ResolutionRunEventIdentityGenerator
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.controlplane.resolution.application.ResolutionRunTransitionRepository
import org.ergon.resolution.domain.ResolutionRetryPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wires explicit human follow-up after automated recovery is exhausted. */
@Configuration(proxyBeanMethods = false)
class ResolutionRunEscalationConfiguration {
    @Bean
    fun resolutionRunEscalationIdentityGenerators(
        eventIdentities: ResolutionRunEventIdentityGenerator,
        followUpIdentities: HumanFollowUpWorkItemIdentityGenerator,
    ) = ResolutionRunEscalationIdentityGenerators(eventIdentities, followUpIdentities)

    @Bean
    fun resolutionRunEscalationRecords(
        runs: ResolutionRunRepository,
        transitions: ResolutionRunTransitionRepository,
        escalations: ResolutionRunEscalationRepository,
        followUps: HumanFollowUpWorkItemRepository,
        authorities: HumanAuthorityRepository,
    ) = ResolutionRunEscalationRecords(runs, transitions, escalations, followUps, authorities)

    @Bean
    fun resolutionRunEscalationService(
        records: ResolutionRunEscalationRecords,
        retryPolicy: ResolutionRetryPolicy,
        identities: ResolutionRunEscalationIdentityGenerators,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = ResolutionRunEscalationService(
        records,
        retryPolicy,
        identities,
        transactionRunner,
        clock,
    )
}
