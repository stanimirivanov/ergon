package org.ergon.controlplane.resolution

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.resolution.application.ApprovalDecisionRepository
import org.ergon.controlplane.resolution.application.ApprovalRequestRepository
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantIdentityGenerator
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantRecords
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantRepository
import org.ergon.controlplane.resolution.application.CapabilityAuthorizationGrantService
import org.ergon.controlplane.resolution.application.ResolutionRunRepository
import org.ergon.resolution.domain.CapabilityAuthorizationGrantId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/** Wires the authorization-grant boundary separately from approval decision recording. */
@Configuration(proxyBeanMethods = false)
class CapabilityAuthorizationConfiguration {
    @Bean
    fun capabilityAuthorizationGrantIdentityGenerator(): CapabilityAuthorizationGrantIdentityGenerator =
        CapabilityAuthorizationGrantIdentityGenerator {
            CapabilityAuthorizationGrantId(UUID.randomUUID())
        }

    @Bean
    fun capabilityAuthorizationGrantRecords(
        decisions: ApprovalDecisionRepository,
        requests: ApprovalRequestRepository,
        runs: ResolutionRunRepository,
        grants: CapabilityAuthorizationGrantRepository,
    ) = CapabilityAuthorizationGrantRecords(decisions, requests, runs, grants)

    @Bean
    fun capabilityAuthorizationGrantService(
        records: CapabilityAuthorizationGrantRecords,
        identities: CapabilityAuthorizationGrantIdentityGenerator,
        transactionRunner: TransactionRunner,
        clock: Clock,
    ) = CapabilityAuthorizationGrantService(records, identities, transactionRunner, clock)
}
