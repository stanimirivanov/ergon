package org.ergon.controlplane.followup

import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.followup.application.HumanFollowUpClaimRepository
import org.ergon.controlplane.followup.application.RequesterCaseWriteGuard
import org.ergon.controlplane.followup.application.RequesterInformationRequestRepository
import org.ergon.controlplane.followup.application.RequesterInformationRequestService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Composes the information-request command without infrastructure in application policy. */
@Configuration(proxyBeanMethods = false)
class RequesterInformationRequestConfiguration {
    @Bean
    fun requesterInformationRequests(
        claims: HumanFollowUpClaimRepository,
        cases: RequesterCaseWriteGuard,
        requests: RequesterInformationRequestRepository,
        transactions: TransactionRunner,
        clock: Clock,
    ): RequesterInformationRequestService =
        RequesterInformationRequestService(
            claims,
            cases,
            requests,
            transactions,
            clock,
        )
}
